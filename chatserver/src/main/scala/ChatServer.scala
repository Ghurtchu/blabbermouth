import cats.effect._
import cats.effect.std.Queue
import cats.implicits.{catsSyntaxOptionId, catsSyntaxParallelTraverse1, toTraverseOps}
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
import ws.WsMessage._
import org.http4s.websocket.WebSocketFrame.Text
import redis.PubSubMessage

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
  type Flow = EffectfulPipe[WebSocketFrame]
  type PubSubFlow = EffectfulPipe[String]
  type ChatQueue = Queue[IO, WsMessage]

  val redisLocation = "redis://redis"

  def generateRandomId: IO[String] = IO.delay(java.util.UUID.randomUUID().toString.replaceAll("-", ""))

  val redis: Resource[IO, SortedSetCommands[IO, String, String]] =
    RedisClient[IO]
      .from(redisLocation)
      .flatMap(Redis[IO].fromClient(_, RedisCodec.Utf8))

  val run = (for {
    pubSubFlow <- RedisClient[IO]
      .from(redisLocation)
      .flatMap(PubSub.mkPubSubConnection[IO, String, String](_, RedisCodec.Utf8).map(_.publish(RedisChannel("joins"))))
    chatHistoryRef <- Resource.eval(IO.ref(Map.empty[UserId, ChatHistory]))
    chatQsRef <- Resource.eval(IO.ref(Map.empty[ChatId, ChatQueue]))
    _ <- EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"9000")
      .withHttpWebSocketApp(webSocketApp(_, chatHistoryRef, pubSubFlow, chatQsRef))
      .withIdleTimeout(300.seconds)
      .build
    _ <- (for {
      now <- IO.realTimeInstant.flatTap(now => IO.println(s"Running cache cleanup for ChatHistory: $now"))
      _ <- chatHistoryRef.getAndUpdate {
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
  def findById[A](cache: Ref[IO, Map[String, A]])(id: String): IO[Option[A]] = cache.get.flatMap(c => IO(c.get(id)))

  private def webSocketApp(
    wsb: WebSocketBuilder2[IO],
    chatHistoryRef: Ref[IO, Map[ChatId, ChatHistory]],
    pubSubFlow: PubSubFlow,
    chatQsRef: Ref[IO, Map[ChatId, Queue[IO, WsMessage]]],
  ): HttpApp[IO] = {
    val dsl = new Http4sDsl[IO] {}
    import dsl._
    HttpRoutes.of[IO] {
      case GET -> Root / "user" / "join" / username =>
        IO.both(generateRandomId, generateRandomId).flatMap { case (userId, chatId) =>
          for {
            _ <- IO.println(s"Registering $username in the system")
            _ <- chatHistoryRef
              .updateAndGet(_.updated(chatId, ChatHistory.init(username, userId, chatId)))
              .flatTap(cache => IO.println(s"ChatHistory cache: $cache"))
            chatQ <- Queue.unbounded[IO, WsMessage]
            _ <- chatQsRef
              .updateAndGet(_.updated(chatId, chatQ))
              .flatTap(cache => IO.println(s"ChatQueue cache: $cache"))
            _ <- IO.println(s"Registered $username in the system")
            response <- Ok(Registered(userId, username, chatId).asJson)
          } yield response
        }
      case GET -> Root / "chat" / chatId =>
        val maybeQ = findById(chatQsRef)(chatId)
        val maybeChatHistory = findById(chatHistoryRef)(chatId)

        val lazyReceive: IO[Flow] = maybeQ.map {
          case Some(queue) =>
            (frames: Frames) =>
              frames.evalMap { case Text(body, _) =>
                val request = Json.parse(body).as[WsRequestBody]
                println(s"processing ${request.`type`} message")
                request.args match {
                  // User joins for the first time
                  case Join(u @ ChatParticipant.User, userId, username, None, None) =>
                    for {
                      _ <- IO.println(s"User with userId: $userId is attempting to join the chat server")
                      joined = Joined(u, userId, chatId, None, None)
                      user = User(username, userId, chatId)
                      pubSubMsgAsJson = PubSubMessage[User]("UserJoined", user).asJson
                      _ <- IO.println(s"Writing $joined into Redis SortedSet with Score 0 - pending")
                      _ <- redis.use(_.zAdd("users", None, ScoreWithValue(Score(0), user.asJson)))
                      _ <- IO.println(s"""Publishing $pubSubMsgAsJson into Redis pub/sub "users" channel""")
                      _ <- Stream.emit(pubSubMsgAsJson).through(pubSubFlow).compile.drain
                      _ <- queue offer joined
                    } yield ()
                  // User re-joins (browser refresh), so we load chat history
                  // TODO: consider storing chat history in the browser local storage
                  case Join(ChatParticipant.User, _, _, Some(_), _) => maybeChatHistory.flatMap(_.fold(queue.offer(ChatExpired(chatId)))(queue.offer))
                  // Support joins for the first time
                  case Join(s @ Support, userId, username, None, u @ Some(_)) =>
                    for {
                      _ <- IO.println(s"Support attempting to join user with userId: $userId")
                      joined <- generateRandomId.map(id => Joined(s, userId, chatId, Some(id), u))
                      _ <- IO.println(s"Support joined the user with userId: $userId")
                      _ <- List.fill(2)(joined).parTraverse(queue.offer)
                      pubSubMessage = PubSubMessage[User]("SupportJoined", User(username, userId, chatId)).asJson
                      _ <- Stream.emit(pubSubMessage).through(pubSubFlow).compile.drain
                    } yield ()
                  // Support re-joins (browser refresh), so we load chat history
                  // TODO: consider storing chat history in the browser local storage
                  case Join(Support, _, _, Some(_), Some(_)) => maybeChatHistory.flatMap(_.fold(queue.offer(ChatExpired(chatId)))(queue.offer))
                  // chat message either from user or support
                  case msg: ChatMessage =>
                    for {
                      now <- IO.realTimeInstant
                      _ <- IO.println(s"participant:${msg.from} sent message:${msg.content} to:${msg.from.mirror} at:$now")
                      msgWithTimestamp = msg.copy(timestamp = Some(now))
                      _ <- maybeChatHistory.flatMap {
                        case Some(history) =>
                          chatHistoryRef
                            .updateAndGet(_.updated(chatId, history + msgWithTimestamp))
                            .flatTap(cache => IO.println(s"ChatHistory cache: $cache"))
                            .as(msgWithTimestamp)
                        case None => IO.pure(ChatExpired(chatId)).flatTap(ce => IO.println(s"Chat was expired: $ce"))
                      }
                      _ <- List.fill(2)(msgWithTimestamp).parTraverse(queue.offer)
                    } yield ()
                }
              }
          case None => _ => Stream.empty
        }
        val lazySend =
          maybeQ.map(_.fold[Frames](Stream.empty)(q => Stream.repeatEval(q.take).collect { case o: Out => WsResponseBody(o).asJson.asText }))

        val receive = (frames: Frames) => Stream.eval(lazyReceive).flatMap(_(frames))
        val send = Stream.eval(lazySend).flatten

        wsb
          .withOnClose {
            chatHistoryRef.get.map(_.get(chatId)).flatMap {
              case Some(ChatHistory(user: User, _)) =>
                for {
                  _ <- redis.use(_.zAdd("users", None, ScoreWithValue(Score(1), user.asJson)))
                  _ <- publishUserLeft(user, pubSubFlow)
                } yield ()
              case None => IO.unit
            }
          }
          .build(send, receive)
    }
  }.orNotFound

  private def publishUserLeft(
    user: User,
    pubSubFlow: PubSubFlow,
  ): IO[Unit] =
    IO.println(s"User left the chat, chat id: ${user.chatId}") *> Stream
      .emit(PubSubMessage[User]("UserLeft", user).asJson)
      .through(pubSubFlow)
      .compile
      .drain
}
