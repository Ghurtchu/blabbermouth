import cats.effect._
import cats.implicits.{catsSyntaxOptionId, toFoldableOps, toTraverseOps}
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
import dev.profunktor.redis4cats.effects._
import fs2.{Pipe, Stream}
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import play.api.libs.json._
import dev.profunktor.redis4cats.pubsub.PubSub
import domain.ChatParticipant._
import domain.{ChatParticipant, User}
import fs2.concurrent.Topic
import ws.WsMessage._
import org.http4s.websocket.WebSocketFrame.Text
import redis.PubSubMessage

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.DurationInt

/**   - publishes Join message to Redis pub/sub
  *   - serves chat functionality via WebSockets
  */
object ChatServer extends IOApp.Simple {

  type UserId = String
  type ChatId = String
  type Frames = Stream[IO, WebSocketFrame]
  type EffectfulPipe[A] = Pipe[IO, A, Unit]
  type WebSocketFlow = EffectfulPipe[WebSocketFrame]
  type Publisher = EffectfulPipe[String]

  val redisLocation = "redis://redis"

  def generateRandomId: IO[String] = IO.delay {
    java.util.UUID
      .randomUUID()
      .toString
      .replaceAll("-", "")
  }

  val redis: Resource[IO, SortedSetCommands[IO, String, String]] =
    RedisClient[IO]
      .from(redisLocation)
      .flatMap(Redis[IO].fromClient(_, RedisCodec.Utf8))

  val channel = RedisChannel("joins")

  private case class PingPong(userTimeStamp: Option[Instant], supportTimestamp: Option[Instant])
  private object PingPong {
    def empty: PingPong = new PingPong(None, None)
  }

  val run = (for {
    publisher <- RedisClient[IO]
      .from(redisLocation)
      .flatMap(PubSub.mkPubSubConnection[IO, String, String](_, RedisCodec.Utf8).map(_.publish(channel)))
    chatHistoryRef <- Resource.eval(IO.ref(Map.empty[UserId, ChatHistory]))
    chatTopicRef <- Resource.eval(IO.ref(Map.empty[ChatId, Topic[IO, Option[WsMessage]]]))
    pingPongRef <- Resource.eval(IO.ref(Map.empty[ChatId, PingPong]))
    _ <- EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"9000")
      .withHttpWebSocketApp(webSocketApp(_, chatHistoryRef, publisher, pingPongRef, chatTopicRef))
      .withIdleTimeout(300.seconds)
      .build
    _ <- (for {
      now <- IO.realTimeInstant.flatTap(now => IO.println(s"Running cache cleanup for ChatHistory: $now"))
      _ <- chatHistoryRef.update {
        _.flatMap { case (userId, chatHistory) =>
          chatHistory.messages.lastOption
            .fold((userId -> chatHistory).some) {
              _.timestamp.flatMap { timestamp =>
                val diffInMinutes = ChronoUnit.MINUTES.between(timestamp, now)
                Option.when(diffInMinutes < 2L)(userId -> chatHistory)
              }
            }
        }
      }
    } yield ()).flatMap(_ => IO.sleep(2.minutes)).foreverM.toResource
  } yield ExitCode.Success).useForever

  implicit class JsonSyntax[A: Writes](self: A) { def asJson: String = Json.stringify(Json.toJson(self)) }
  implicit class WebSocketTextSyntax(self: String) { def asText: Text = Text(self) }
  def findById[A](cache: Ref[IO, Map[String, A]])(id: String): IO[Option[A]] = cache.get map (_ get id)
  private def updatePingPongRef(
    pingPongRef: Ref[IO, Map[ChatId, PingPong]],
    chatId: ChatId,
    update: PingPong => PingPong,
    default: PingPong,
  ): IO[Unit] = pingPongRef.update {
    _.updatedWith(chatId) {
      case Some(pp) => Some(update(pp))
      case None     => Some(default)
    }
  }

  private def webSocketApp(
    wsb: WebSocketBuilder2[IO],
    chatHistoryRef: Ref[IO, Map[ChatId, ChatHistory]],
    publisher: Publisher,
    pingPongRef: Ref[IO, Map[ChatId, PingPong]],
    chatTopicRef: Ref[IO, Map[ChatId, Topic[IO, Option[WsMessage]]]],
  ): HttpApp[IO] = {
    val dsl = new Http4sDsl[IO] {}
    import dsl._
    HttpRoutes.of[IO] {
      case GET -> Root / "user" / "join" / username =>
        IO.both(generateRandomId, generateRandomId).flatMap { case (userId, chatId) =>
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
                  _ <- updatePingPongRef(pingPongRef, chatId, _.copy(userTimeStamp = now.some), PingPong(now.some, None))
                } yield None
              case Text("pong:support", _) =>
                for {
                  now <- IO.realTimeInstant
                  _ <- updatePingPongRef(pingPongRef, chatId, _.copy(supportTimestamp = now.some), PingPong(None, now.some))
                } yield None
              case Text(body, _) =>
                val request = Json.parse(body).as[WsRequestBody]
                println(s"processing ${request.`type`} message")
                request.args match {
                  // User joins for the first time
                  case Join(ChatParticipant.User, userId, username, None, None) =>
                    for {
                      _ <- IO.println(s"User with userId: $userId is attempting to join the chat server")
                      user = User(username, userId, chatId)
                      pubSubMsgAsJson = PubSubMessage[User]("UserJoined", user).asJson
                      _ <- IO.println(s"Writing $user into Redis SortedSet with Score 0 - pending")
                      _ <- redis.use(_.zAdd("users", None, ScoreWithValue(Score(0), user.asJson)))
                      _ <- IO.println(s"""Publishing $pubSubMsgAsJson into Redis pub/sub "users" channel""")
                      _ <- Stream.emit(pubSubMsgAsJson).through(publisher).compile.drain
                    } yield None
                  // User re-joins (browser refresh), so we load chat history
                  // TODO: consider storing chat history in the browser local storage
                  case Join(ChatParticipant.User, _, _, Some(_), _) =>
                    maybeChatHistory.map(_.getOrElse(ChatExpired(chatId))).map(_.some)
                  // Support joins for the first time
                  case Join(ChatParticipant.Support, userId, username, None, Some(su)) =>
                    for {
                      _ <- IO.println(s"Support attempting to join user with userId: $userId")
                      id <- generateRandomId
                      _ <- IO.println(s"Support joined the user with userId: $userId")
                      pubSubMessage = PubSubMessage[domain.Support](
                        "SupportJoinedUser",
                        domain.Support(id, su, User(username, userId, chatId)),
                      ).asJson
                      _ <- Stream.emit(pubSubMessage).through(publisher).compile.drain
                    } yield None
                  // Support re-joins (browser refresh), so we load chat history
                  // TODO: consider storing chat history in the browser local storage
                  case Join(ChatParticipant.Support, _, _, Some(_), Some(_)) =>
                    maybeChatHistory.map(_.getOrElse(ChatExpired(chatId))).map(_.some)
                  // chat message either from user or support
                  case msg: ChatMessage =>
                    for {
                      now <- IO.realTimeInstant
                      _ <- IO.println(s"participant:${msg.from} sent message:${msg.content} to:${msg.from.mirror} at:$now")
                      msgWithTimestamp = msg.copy(timestamp = Some(now))
                      _ <- maybeChatHistory.flatMap {
                        case Some(history) =>
                          chatHistoryRef
                            .update(_.updated(chatId, history + msgWithTimestamp))
                            .flatTap(cache => IO.println(s"ChatHistory cache: $cache"))
                            .as(msgWithTimestamp)
                        case None => IO.pure(ChatExpired(chatId)).flatTap(ce => IO.println(s"Chat was expired: $ce"))
                      }
                    } yield msgWithTimestamp.some
                }
            })
          case None => _ => Stream.empty
        }

        val lazySend = maybeTopic.map {
          _.fold[Frames](Stream.empty) {
            _.subscribe(10)
              .collect { case Some(o: Out) => WsResponseBody(o).asJson.asText }
              .merge {
                Stream
                  .awakeEvery[IO](4.seconds)
                  .as(WebSocketFrame.Text("ping"))
              }
          }
        }

        val receive: Frames => Stream[IO, Unit] = (frames: Frames) => Stream.eval(lazyReceive).flatMap(_(frames))
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
                    userLeftChat(chatHistoryRef, pingPongRef, chatId, topic, publisher)
                  else
                    supportLeftChat(topic, pingPongRef, chatId)
                case PingPong(Some(_), _) => supportLeftChat(topic, pingPongRef, chatId)
                case PingPong(_, Some(_)) => userLeftChat(chatHistoryRef, pingPongRef, chatId, topic, publisher)
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
        _ <- redis.use(_.zAdd("users", None, ScoreWithValue(Score(1), user.asJson)))
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
