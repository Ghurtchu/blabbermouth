import cats.MonadThrow
import cats.effect._
import cats.implicits.{catsSyntaxOptionId, catsSyntaxTuple2Semigroupal, toFoldableOps, toTraverseOps}
import ws.{WsMessage, WsRequestBody, WsResponseBody}
import ws.WsMessage.Out.codecs._
import ws.WsMessage.In._
import ws.WsMessage.Out._
import com.comcast.ip4s.IpLiteralSyntax
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.SortedSetCommands
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.{RedisChannel, RedisCodec}
import dev.profunktor.redis4cats.effect.Log.NoOp.instance
import dev.profunktor.redis4cats.effect.MkRedis
import dev.profunktor.redis4cats.effects._
import fs2.{Pipe, Stream}
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import play.api.libs.json._
import dev.profunktor.redis4cats.pubsub.PubSub
import dev.profunktor.redis4cats.tx.TxStore
import domain.ChatParticipant._
import domain.{ChatParticipant, User}
import fs2.concurrent.Topic
import ws.WsMessage._
import org.http4s.websocket.WebSocketFrame.Text
import redis.PubSubMessage

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.Option.when
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**   - publishes Join message to Redis pub/sub
  *   - serves chat functionality via WebSockets
  */
object ChatServer extends IOApp.Simple {

  private type UserId = String
  private type ChatId = String
  private type WebSocketFrames = Stream[IO, WebSocketFrame]
  private type WebSocketFlow = Pipe[IO, WebSocketFrame, Unit]
  private type Publisher = Pipe[IO, String, Unit]

  val redisLocation = "redis://redis"

  def generateRandomId: IO[String] = IO.delay {
    java.util.UUID
      .randomUUID()
      .toString
      .replaceAll("-", "")
  }

  private case class PingPong(
    userTimeStamp: Option[Instant],
    supportTimestamp: Option[Instant],
  )
  private object PingPong {
    def empty: PingPong = new PingPong(None, None)
  }

  private def mkPublisher(
    redis: RedisClient,
    channel: RedisChannel[String],
  ): Resource[IO, Publisher] =
    PubSub
      .mkPubSubConnection[IO, String, String](
        redis,
        RedisCodec.Utf8,
      )
      .map(_ publish channel)

  private def mkSortedSetCommandsClient(
    redis: RedisClient,
  ): Resource[IO, SortedSetCommands[IO, String, String]] =
    Redis[IO]
      .fromClient(
        redis,
        RedisCodec.Utf8,
      )

  private def mkRef[K, V]: Resource[IO, Ref[IO, Map[K, V]]] =
    Resource.eval(IO.ref(Map.empty[K, V]))
  private def mkRedisClient(redisLocation: String): Resource[IO, RedisClient] =
    RedisClient[IO] from redisLocation

  private def runCacheCleanupPeriodically(
    chatHistoryRef: Ref[IO, Map[UserId, ChatHistory]],
    duration: FiniteDuration,
  ): Resource[IO, Nothing] =
    (for {
      now <- IO.realTimeInstant
      _ <- IO.println(s"Running cache cleanup for ChatHistory: $now")
      _ <- chatHistoryRef.update {
        _.flatMap { case (userId, chatHistory) =>
          chatHistory.messages.lastOption
            .fold((userId -> chatHistory).some) {
              _.timestamp.flatMap { timestamp =>
                val diffInMinutes = ChronoUnit.MINUTES.between(timestamp, now)
                when(diffInMinutes < 2L)(userId -> chatHistory)
              }
            }
        }
      }
    } yield ())
      .flatMap(_ => IO.sleep(duration))
      .foreverM
      .toResource

  val run = (for {
    redisClient <- mkRedisClient(redisLocation)
    publisher <- mkPublisher(redisClient, RedisChannel("joins"))
    redisCmdClient <- mkSortedSetCommandsClient(redisClient)
    chatHistoryRef <- mkRef[UserId, ChatHistory]
    chatTopicRef <- mkRef[ChatId, Topic[IO, Option[WsMessage]]]
    pingPongRef <- mkRef[ChatId, PingPong]
    _ <- EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"9000")
      .withHttpWebSocketApp(webSocketApp(_, redisCmdClient, chatHistoryRef, publisher, pingPongRef, chatTopicRef))
      .withIdleTimeout(300.seconds)
      .build
    _ <- runCacheCleanupPeriodically(chatHistoryRef, 2.minutes)
  } yield ExitCode.Success).useForever

  implicit class JsonSyntax[A: Writes](self: A) { def asJson: String = Json stringify (Json toJson self) }
  implicit class WebSocketTextSyntax(self: String) { def asText: Text = Text(self) }
  private def findById[A](cache: Ref[IO, Map[String, A]])(id: String): IO[Option[A]] = cache.get map (_ get id)
  private def updatePingPongRef(
    pingPongRef: Ref[IO, Map[ChatId, PingPong]],
    chatId: ChatId,
    update: PingPong => PingPong,
    initial: PingPong,
  ): IO[Unit] =
    pingPongRef.update(_.updatedWith(chatId)(_.map(update).getOrElse(initial).some))

  private def webSocketApp(
    wsb: WebSocketBuilder2[IO],
    redisCmdClient: SortedSetCommands[IO, String, String],
    chatHistoryRef: Ref[IO, Map[ChatId, ChatHistory]],
    publisher: Publisher,
    pingPongRef: Ref[IO, Map[ChatId, PingPong]],
    chatTopicRef: Ref[IO, Map[ChatId, Topic[IO, Option[WsMessage]]]],
  ): HttpApp[IO] = {
    val dsl = new Http4sDsl[IO] {}
    import dsl._
    HttpRoutes.of[IO] {
      case GET -> Root / "user" / "join" / username =>
        (
          generateRandomId,
          generateRandomId,
        ).flatMapN { case (userId, chatId) =>
          for {
            _ <- IO.println(s"Registering $username in the system")
            _ <- chatHistoryRef
              .update(_.updated(chatId, ChatHistory.init(username, userId, chatId)))
              .flatTap(cache => IO.println(s"ChatHistory cache: $cache"))
            topic <- Topic[IO, Option[WsMessage]]
            _ <- chatTopicRef
              .update(_.updated(chatId, topic))
              .flatTap(cache => IO.println(s"ChatTopics cache: $cache"))
            _ <- IO.println(s"Registered $username in the system")
            response <- Ok(Registered(userId, username, chatId).asJson)
          } yield response
        }
      case GET -> Root / "chat" / chatId =>
        val maybeChatHistory = findById(chatHistoryRef)(chatId)
        val maybeTopic = findById(chatTopicRef)(chatId)

        val lazyReceive: IO[WebSocketFlow] = maybeTopic.map {
          case Some(topic) =>
            topic.publish.compose[Stream[IO, WebSocketFrame]](_.evalMap {
              case Text("pong:user", _) =>
                for {
                  now <- IO.realTimeInstant
                  _ <- updatePingPongRef(
                    pingPongRef,
                    chatId,
                    _.copy(userTimeStamp = now.some),
                    PingPong(now.some, None),
                  )
                } yield None
              case Text("pong:support", _) =>
                for {
                  now <- IO.realTimeInstant
                  _ <- updatePingPongRef(
                    pingPongRef,
                    chatId,
                    _.copy(supportTimestamp = now.some),
                    PingPong(None, now.some),
                  )
                } yield None
              case Text(msg, _) =>
                val body = Json.parse(msg).as[WsRequestBody]
                println(s"processing ${body.`type`} message")
                body.args match {
                  // User joins for the first time
                  case Join(ChatParticipant.User, userId, username, None, None) =>
                    for {
                      _ <- IO.println(s"User with userId: $userId is attempting to join the chat server")
                      user = User(username, userId, chatId)
                      pubSubMsgAsJson = PubSubMessage[User]("UserJoined", user).asJson
                      _ <- IO.println(s"Writing $user into Redis SortedSet with Score 0 - pending")
                      _ <- redisCmdClient.zAdd("users", None, ScoreWithValue(Score(0), user.asJson))
                      _ <- IO.println(s"""Publishing $pubSubMsgAsJson into Redis pub/sub "users" channel""")
                      _ <- Stream.emit(pubSubMsgAsJson).through(publisher).compile.drain
                    } yield UserJoined(user).some
                  // User re-joins (browser refresh), so we load chat history
                  // TODO: consider storing chat history in the browser local storage
                  case Join(ChatParticipant.User, _, _, Some(_), _) =>
                    maybeChatHistory.map(_.getOrElse(ChatExpired(chatId))).map(_.some)
                  // Support joins for the first time
                  case Join(ChatParticipant.Support, userId, username, None, Some(su)) =>
                    for {
                      _ <- IO.println(s"Support attempting to join user with userId: $userId")
                      id <- generateRandomId
                      support = domain.Support(id, su, User(username, userId, chatId))
                      pubSubMessage = PubSubMessage[domain.Support]("SupportJoinedUser", support).asJson
                      _ <- Stream.emit(pubSubMessage).through(publisher).compile.drain
                      _ <- IO.println(s"Support joined the user with userId: $userId")
                    } yield SupportJoined(support).some
                  // Support re-joins (browser refresh), so we load chat history
                  // TODO: consider storing chat history in the browser local storage
                  case Join(ChatParticipant.Support, _, _, Some(_), Some(_)) =>
                    maybeChatHistory.map(_.getOrElse(ChatExpired(chatId))).map(_.some)
                  // chat message either from user or support
                  case msg: ChatMessage =>
                    for {
                      now <- IO.realTimeInstant
                      _ <- IO.println(s"participant:${msg.from} sent message:${msg.content} to:${msg.from.mirror} at:$now")
                      newMessage = msg.copy(timestamp = Some(now))
                      _ <- maybeChatHistory.flatMap {
                        case Some(messages) =>
                          chatHistoryRef
                            .update(_.updated(chatId, messages + newMessage))
                            .flatTap(cache => IO.println(s"ChatHistory cache: $cache"))
                            .as(newMessage)
                        case None => IO.pure(ChatExpired(chatId))
                      }
                    } yield newMessage.some
                }
            })
          case None => (_: WebSocketFrames) => Stream.empty
        }

        val lazySend = maybeTopic.map {
          _.fold[WebSocketFrames](Stream.empty) {
            _.subscribe(10)
              .collect { case Some(o: Out) => WsResponseBody(o).asJson.asText }
              .merge {
                Stream
                  .awakeEvery[IO](4.seconds)
                  .as(WebSocketFrame.Text("ping"))
              }
          }
        }

        val receive: WebSocketFrames => Stream[IO, Unit] = (frames: WebSocketFrames) => Stream.eval(lazyReceive).flatMap(_(frames))
        val send: Stream[IO, WebSocketFrame] = Stream.eval(lazySend).flatten

        wsb
          .withOnClose {
            for {
              _ <- IO.println("Websocket connection was closed")
              // semantically blocks, to make sure that we received latest pong from user or support
              _ <- IO.sleep(5.seconds)
              topic <- maybeTopic
              maybePingPong <- pingPongRef.get.map(_ get chatId)
              _ <- IO.println(s"PingPong: $maybePingPong")
              _ <- maybePingPong.traverse {
                case PingPong(Some(userTimeStamp), Some(supportTimestamp)) =>
                  if (userTimeStamp.isBefore(supportTimestamp))
                    userLeftChat(redisCmdClient, chatHistoryRef, pingPongRef, chatId, topic, publisher)
                  else
                    supportLeftChat(topic, pingPongRef, chatId)
                case PingPong(Some(_), _) => supportLeftChat(topic, pingPongRef, chatId)
                case PingPong(_, Some(_)) => userLeftChat(redisCmdClient, chatHistoryRef, pingPongRef, chatId, topic, publisher)
                case _                    => IO.unit
              }
            } yield ()
          }
          .build(send, receive)
    }
  }.orNotFound

  private def supportLeftChat(
    topic: Option[Topic[IO, Option[WsMessage]]],
    pingPongRef: Ref[IO, Map[ChatId, PingPong]],
    chatId: String,
  ): IO[Unit] = for {
    _ <- IO.println("support has left the chat")
    _ <- pingPongRef.update(_.updated(chatId, PingPong.empty))
    _ <- topic.traverse_(_.publish1(Out.SupportLeft(chatId).some))
  } yield ()

  private def userLeftChat(
    redisCmdClient: SortedSetCommands[IO, String, String],
    chatHistoryRef: Ref[IO, Map[ChatId, ChatHistory]],
    pingPongRef: Ref[IO, Map[ChatId, PingPong]],
    chatId: String,
    topic: Option[Topic[IO, Option[WsMessage]]],
    publisher: Publisher,
  ): IO[Unit] = for {
    _ <- IO.println("user has left the chat")
    _ <- pingPongRef.update(_.updated(chatId, PingPong.empty))
    chatHistory <- chatHistoryRef.get.map(_.get(chatId))
    _ <- chatHistory.traverse { case ChatHistory(user: User, _) =>
      for {
        _ <- redisCmdClient.zAdd("users", None, ScoreWithValue(Score(1), user.asJson))
        _ <- topic.traverse_(_.publish1(Out.UserLeft(chatId).some))
        _ <- publishMsgToRedisPubSub[PubSubMessage.UserLeft](PubSubMessage.UserLeft(user), publisher)
      } yield ()
    }
  } yield ()

  private def publishMsgToRedisPubSub[A: Writes](
    message: A,
    publisher: Publisher,
  ): IO[Unit] =
    IO.println(s"${message.getClass.getSimpleName} left the chat: $message") *> Stream
      .emit(PubSubMessage[A](message.getClass.getSimpleName, message).asJson)
      .through(publisher)
      .compile
      .drain
}
