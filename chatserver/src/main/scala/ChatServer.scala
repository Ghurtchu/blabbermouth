import cats.effect._
import cats.syntax.all._
import ws.{Message, PingPong, WebSocketTextSyntax, WsRequestBody, WsResponseBody}
import ws.Message.Out.codecs._
import ws.Message.In._
import ws.Message.Out._
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
import domain.{ChatParticipant, User}
import fs2.concurrent.Topic
import ws.Message._
import ws._
import org.http4s.websocket.WebSocketFrame.Text
import redis.PubSubMessage

import scala.concurrent.duration.DurationInt

/**   - publishes Join message to Redis pub/sub
  *   - serves chat functionality via WebSockets
  */
object ChatServer extends IOApp.Simple {

  private type UserId = String
  type ChatId = String
  private type WebSocketFrames = Stream[IO, WebSocketFrame]
  private type Flow[A] = Pipe[IO, A, Unit]
  private type WebSocketFrameFlow = Flow[WebSocketFrame]
  type Publisher = Flow[String]

  val redisLocation = "redis://redis"

  def generateRandomId: IO[String] = IO.delay {
    java.util.UUID
      .randomUUID()
      .toString
      .replaceAll("-", "")
  }

  private def mkPublisher(
    redis: RedisClient,
    channel: RedisChannel[String],
  ): Resource[IO, Publisher] =
    PubSub
      .mkPubSubConnection[IO, String, String](redis, RedisCodec.Utf8)
      .map(_.publish(channel))

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
    RedisClient[IO].from(redisLocation)

  val run = (for {
    redisClient <- mkRedisClient(redisLocation)
    publisher <- mkPublisher(redisClient, RedisChannel("joins"))
    redisCmdClient <- mkSortedSetCommandsClient(redisClient)
    chatHistoryRef <- mkRef[UserId, ChatHistory]
    chatTopicRef <- mkRef[ChatId, Topic[IO, Message]]
    pingPongRef <- mkRef[ChatId, PingPong]
    _ <- EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"9000")
      .withHttpWebSocketApp(webSocketApp(_, redisCmdClient, chatHistoryRef, publisher, pingPongRef, chatTopicRef))
      .withIdleTimeout(300.seconds)
      .build
  } yield ExitCode.Success).useForever

  private def findById[A](cache: Ref[IO, Map[String, A]])(id: String): IO[Option[A]] =
    cache.get.map(_.get(id))

  private def updatePingPongRef(
    pingPongRef: Ref[IO, Map[ChatId, PingPong]],
    chatId: ChatId,
    updateFunction: PingPong => PingPong,
    initial: PingPong,
  ): IO[Unit] =
    pingPongRef.update(_.updatedWith(chatId)(_.map(updateFunction).getOrElse(initial).some))

  private def webSocketApp(
    wsb: WebSocketBuilder2[IO],
    redisCmdClient: SortedSetCommands[IO, String, String],
    chatHistoryRef: Ref[IO, Map[ChatId, ChatHistory]],
    publisher: Publisher,
    pingPongRef: Ref[IO, Map[ChatId, PingPong]],
    chatTopicRef: Ref[IO, Map[ChatId, Topic[IO, Message]]],
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
            topic <- Topic[IO, Message]
            _ <- chatTopicRef
              .update(_.updated(chatId, topic))
              .flatTap(cache => IO.println(s"ChatTopics cache: $cache"))
            _ <- IO.println(s"Registered $username in the system")
            response <- Ok(Registered(userId, username, chatId).toJson)
          } yield response
        }
      case GET -> Root / "chat" / chatId =>
        val maybeChatHistory = findById(chatHistoryRef)(chatId)
        val maybeTopic = findById(chatTopicRef)(chatId)

        val lazyReceive: IO[WebSocketFrameFlow] = maybeTopic.map {
          case Some(topic) =>
            topic.publish.compose[WebSocketFrames](_.evalMap {
              case Text("pong:user", _) =>
                IO.realTimeInstant.flatMap(now =>
                  updatePingPongRef(
                    pingPongRef = pingPongRef,
                    chatId = chatId,
                    updateFunction = _.updateUserTimestamp(now),
                    initial = PingPong.initUser(now),
                  ) as Pong,
                )
              case Text("pong:support", _) =>
                IO.realTimeInstant.flatMap(now =>
                  updatePingPongRef(
                    pingPongRef = pingPongRef,
                    chatId = chatId,
                    updateFunction = _.updateSupportTimestamp(now),
                    initial = PingPong.initSupport(now),
                  ) as Pong,
                )
              case Text(body, _) =>
                val msg = body.into[WsRequestBody]
                println(s"processing ${msg.`type`} message")
                msg.args match {
                  // User joins for the first time
                  case Join(ChatParticipant.User, userId, username, None, None) =>
                    for {
                      _ <- IO.println(s"User with userId: $userId is attempting to join the chat server")
                      user = User(username, userId, chatId)
                      pubSubMsgAsJson = PubSubMessage[User]("UserJoined", user).toJson
                      _ <- IO.println(s"Writing $user into Redis SortedSet with Score 0 - pending")
                      _ <- redisCmdClient.zAdd("users", None, ScoreWithValue(Score(0), user.toJson))
                      _ <- IO.println(s"""Publishing $pubSubMsgAsJson into Redis pub/sub "users" channel""")
                      _ <- Stream.emit(pubSubMsgAsJson).through(publisher).compile.drain
                    } yield UserJoined(user)
                  // User re-joins (browser refresh), so we load chat history
                  // TODO: consider storing chat history in the browser local storage
                  case Join(ChatParticipant.User, _, _, Some(_), _) =>
                    maybeChatHistory.map(_.getOrElse(ChatExpired(chatId)))
                  // Support joins for the first time
                  case Join(ChatParticipant.Support, userId, username, None, Some(su)) =>
                    for {
                      _ <- IO.println(s"Support attempting to join user with userId: $userId")
                      id <- generateRandomId
                      support = domain.Support(id, su, User(username, userId, chatId))
                      pubSubMessage = PubSubMessage[domain.Support]("SupportJoinedUser", support).toJson
                      _ <- Stream.emit(pubSubMessage).through(publisher).compile.drain
                      _ <- IO.println(s"Support joined the user with userId: $userId")
                    } yield SupportJoined(support)
                  // Support re-joins (browser refresh), so we load chat history
                  // TODO: consider storing chat history in the browser local storage
                  case Join(ChatParticipant.Support, _, _, Some(_), Some(_)) =>
                    maybeChatHistory.map(_.getOrElse(ChatExpired(chatId)))
                  // chat message either from user or support
                  case msg: ChatMessage =>
                    for {
                      now <- IO.realTimeInstant
                      _ <- IO.println(s"participant:${msg.from} sent message:${msg.content} to:${msg.from.mirror} at:$now")
                      msgWithTimeStamp = msg.copy(timestamp = Some(now))
                      _ <- maybeChatHistory.flatMap {
                        case Some(messages) =>
                          chatHistoryRef
                            .update(_.updated(chatId, messages + msgWithTimeStamp))
                            .flatTap(cache => IO.println(s"ChatHistory cache: $cache"))
                            .as(msgWithTimeStamp)
                        case None => IO.pure(ChatExpired(chatId))
                      }
                    } yield msgWithTimeStamp
                }
            })
          case None => _ => Stream.empty
        }

        val lazySend = maybeTopic.map {
          _.fold[WebSocketFrames](Stream.empty) {
            _.subscribe(10)
              .collect { case o: Out => WsResponseBody(o).toJson.toText }
              .merge {
                // send "ping" message to WS client every 1 second
                val ping = "ping".toText
                Stream.emit(ping) ++
                  Stream.awakeEvery[IO](1.second) as ping
              }
          }
        }

        val receive: WebSocketFrames => Stream[IO, Unit] = (frames: WebSocketFrames) => Stream.eval(lazyReceive).flatMap(_(frames))
        val send: Stream[IO, WebSocketFrame] = Stream.eval(lazySend).flatten

        wsb
          .withOnClose {
            (
              maybeTopic,
              pingPongRef.get.map(_.get(chatId)),
            ).flatMapN {
              case (Some(topic), Some(pingPong)) =>
                WsConnectionClosedAction
                  .of[IO](chatHistoryRef, redisCmdClient, publisher)
                  .react(topic, chatId, pingPong)
              case _ => IO.unit
            }
          }
          .build(send, receive)
    }
  }.orNotFound
}
