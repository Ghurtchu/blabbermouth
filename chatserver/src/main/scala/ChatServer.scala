import cats.effect._
import com.comcast.ip4s.IpLiteralSyntax
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.SortedSetCommands
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.{RedisChannel, RedisCodec}
import dev.profunktor.redis4cats.effect.Log.NoOp.instance
import dev.profunktor.redis4cats.effects._
import fs2.{Pipe, Stream}
import fs2.concurrent.Topic
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import play.api.libs.json._
import dev.profunktor.redis4cats.pubsub.PubSub

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.DurationInt

/**   - publishes Join message to Redis pub/sub
  *   - serves chat functionality via WebSockets
  */
object ChatServer extends IOApp.Simple {

  type UserId = String
  type ChatId = String
  type ChatTopic = Topic[IO, Message]

  sealed trait Message
  sealed trait In extends Message
  sealed trait Out extends Message
  implicit val outFmt: Writes[Out] = {
    case m: ChatMessage => chatMsgFmt.writes(m)
    case c: Registered  => writesRegistered.writes(c)
    case c: ChatHistory => writesChatHistory.writes(c)
    case j: Joined      => writesJoined.writes(j)
    case c: ChatExpired => writesChatExpired.writes(c)
  }

  sealed trait From {
    override def toString: String = this.getClass.getSimpleName.init // drops dollar sign
    def opposite: From = this match {
      case User    => Support
      case Support => User
    }
  }
  case object User extends From
  case object Support extends From
  object From {
    def fromString: String => Option[From] = PartialFunction.condOpt(_) {
      case "User"    => User
      case "Support" => Support
    }
  }
  private def toFrom: String => JsResult[From] = From.fromString(_).map(JsSuccess(_)).getOrElse(JsError("unrecognized `From` value"))

  implicit val writesParticipant: Writes[From] = p => JsString(p.toString)
  implicit val readsParticipant: Reads[From] = _.validate[String].flatMap(toFrom)

  case class ChatMessage(
    content: String,
    from: From,
    userId: String,
    supportId: String,
    timestamp: Option[Instant] = None,
  ) extends In
      with Out
  implicit val chatMsgFmt: Format[ChatMessage] = Json.format[ChatMessage]

  case class Join(
    from: From,
    userId: String,
    username: String,
    supportId: Option[String],
    supportUserName: Option[String],
  ) extends In
  implicit val readsJoin: Reads[Join] = Json.reads[Join]

  case class Joined(
    participant: From,
    userId: String,
    chatId: String,
    supportId: Option[String],
    supportUserName: Option[String],
  ) extends Out
  implicit val writesJoined: Writes[Joined] = Json.writes[Joined]

  case class Registered(userId: String, username: String, chatId: String) extends Out
  implicit val writesRegistered: Writes[Registered] = Json.writes[Registered]

  case class ChatHistory(chatId: String, messages: Vector[ChatMessage]) extends Out {
    def +(msg: ChatMessage): ChatHistory = copy(messages = messages :+ msg)
  }
  implicit val writesChatHistory: Writes[ChatHistory] = Json.writes[ChatHistory]
  object ChatHistory {
    def init(chatId: String): ChatHistory = new ChatHistory(chatId, Vector.empty)
  }

  case class Request(`type`: String, args: In)
  implicit val readsRequest: Reads[Request] = json =>
    for {
      typ <- (json \ "type").validate[String]
      args = json("args")
      in <- typ match {
        case "Join"        => readsJoin.reads(args)
        case "ChatMessage" => chatMsgFmt.reads(args)
        case _             => JsError("unrecognized type")
      }
    } yield Request(typ, in)

  case class Response(args: Out)
  implicit val writesResponse: Writes[Response] = response => {
    val `type` = response.args.getClass.getSimpleName
    JsObject(Map("type" -> JsString(`type`), "args" -> outFmt.writes(response.args)))
  }

  case class ChatExpired(chatId: String) extends Out
  implicit val writesChatExpired: Writes[ChatExpired] = Json.writes[ChatExpired]

  def generateRandomId: IO[String] = IO.delay(java.util.UUID.randomUUID().toString.replaceAll("-", ""))

  val redis: Resource[IO, SortedSetCommands[IO, String, String]] =
    RedisClient[IO].from("redis://redis").flatMap(Redis[IO].fromClient(_, RedisCodec.Utf8))

  val run = (for {
    redisPubSub <- RedisClient[IO]
      .from("redis://redis")
      .flatMap(PubSub.mkPubSubConnection[IO, String, String](_, RedisCodec.Utf8).map(_.publish(RedisChannel("joins"))))
    chatTopicsRef <- Resource.eval(IO.ref(Map.empty[ChatId, ChatTopic]))
    chatHistoryRef <- Resource.eval(IO.ref(Map.empty[UserId, ChatHistory]))
    _ <- EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"9000")
      .withHttpWebSocketApp(webSocketApp(_, chatTopicsRef, chatHistoryRef, redisPubSub))
      .withIdleTimeout(120.seconds)
      .build
    _ <- (for {
      now <- IO.realTimeInstant.flatTap(now => IO.println(s"Running cache cleanup for ChatHistory: $now"))
      _ <- chatHistoryRef.getAndUpdate {
        _.flatMap { case (userId, chatHistory) =>
          chatHistory.messages.lastOption.fold(Option(userId -> chatHistory)) {
            _.timestamp.flatMap { timestamp =>
              val diffInMinutes = ChronoUnit.MINUTES.between(timestamp, now)
              Option.when(diffInMinutes < 2L)(userId -> chatHistory)
            }
          }
        }
      }
    } yield ()).flatMap(_ => IO.sleep(2.minutes)).foreverM.toResource
  } yield ExitCode.Success).useForever

  implicit class JsonSyntax[A: Writes](self: A) { def asJson: String = Json.prettyPrint(Json.toJson(self)) }
  implicit class WebSocketTextSyntax(self: String) { def asText: WebSocketFrame.Text = WebSocketFrame.Text(self) }
  def findById[A](cache: Ref[IO, Map[String, A]])(id: String): IO[Option[A]] = cache.get.flatMap(c => IO(c.get(id)))

  private def webSocketApp(
    wsb: WebSocketBuilder2[IO],
    chatTopicsRef: Ref[IO, Map[ChatId, ChatTopic]],
    chatHistoryRef: Ref[IO, Map[ChatId, ChatHistory]],
    redisPubSub: Stream[IO, String] => Stream[IO, Unit],
  ): HttpApp[IO] = {
    val dsl = new Http4sDsl[IO] {}
    import dsl._
    HttpRoutes.of[IO] {
      case GET -> Root / "user" / "join" / username =>
        IO.both(generateRandomId, generateRandomId).flatMap { case (userId, chatId) =>
          for {
            _ <- IO.println(s"Registering $username in the system")
            _ <- chatHistoryRef
              .updateAndGet(_.updated(chatId, ChatHistory.init(chatId)))
              .flatTap(cache => IO.println(s"ChatHistory cache: $cache"))
            chatTopic <- Topic[IO, Message]
            _ <- chatTopicsRef
              .updateAndGet(_.updated(chatId, chatTopic))
              .flatTap(cache => IO.println(s"ChatTopic cache: $cache"))
            _ <- IO.println(s"Registered $username in the system")
            response <- Ok(Registered(userId, username, chatId).asJson)
          } yield response
        }
      case GET -> Root / "chat" / chatId =>
        val lazyReceive: IO[Pipe[IO, WebSocketFrame, Unit]] =
          findById(chatTopicsRef)(chatId).map {
            case Some(chat) =>
              chat.publish.compose[Stream[IO, WebSocketFrame]](_.evalMap { case WebSocketFrame.Text(body, _) =>
                val request = Json.parse(body).as[Request]
                println(s"processing ${request.`type`} message")
                request.args match {
                  // User joins for the first time
                  case Join(u @ User, userId, un, None, None) =>
                    for {
                      _ <- IO.println(s"User with userId: $userId is attempting to join the chat server")
                      joined <- IO.pure(Joined(u, userId, chatId, None, None))
                      json = joined.asJson
                      pubSubJson = s"""{"type":"UserJoined","args":$json}"""
                      _ <- IO.println(s"Writing $json into Redis SortedSet with Score 0 - pending")
                      _ <- redis.use(_.zAdd("users", None, ScoreWithValue(Score(0), json)))
                      _ <- IO.println(s"""Publishing $pubSubJson into Redis pub/sub "users" channel""")
                      _ <- Stream.emit(pubSubJson).through(redisPubSub).compile.drain
                    } yield joined
                  // User re-joins (browser refresh), so we load chat history
                  // TODO: consider storing chat history in the browser local storage
                  case Join(User, _, _, Some(_), _) => findById(chatHistoryRef)(chatId).map(_.getOrElse(ChatExpired(chatId)))
                  // Support joins for the first time
                  case Join(s @ Support, userId, _, None, u @ Some(_)) =>
                    for {
                      _ <- IO.println(s"Support attempting to join user with userId: $userId")
                      joined <- generateRandomId.map(id => Joined(s, userId, chatId, Some(id), u))
                      _ <- IO.println(s"Support joined the user with userId: $userId")
                    } yield joined
                  // Support re-joins (browser refresh), so we load chat history
                  // TODO: consider storing chat history in the browser local storage
                  case Join(Support, _, _, Some(_), Some(_)) => findById(chatHistoryRef)(chatId).map(_.getOrElse(ChatExpired(chatId)))
                  // chat message either from user or support
                  case msg: ChatMessage =>
                    for {
                      now <- IO.realTimeInstant
                      _ <- IO.println(s"participant:${msg.from} sent message:${msg.content} to:${msg.from.opposite} at:$now")
                      msgWithTimestamp = msg.copy(timestamp = Some(now))
                      _ <- findById(chatHistoryRef)(chatId).flatMap {
                        case Some(history) =>
                          chatHistoryRef
                            .updateAndGet(_.updated(chatId, history + msgWithTimestamp))
                            .flatTap(cache => IO.println(s"ChatHistory cache: $cache"))
                            .as(msgWithTimestamp)
                        case None => IO.pure(ChatExpired(chatId)).flatTap(ce => IO.println(s"Chat was expired: $ce"))
                      }
                    } yield msg
                }
              })
            case _ => (_: Stream[IO, WebSocketFrame]) => Stream.empty
          }

        val lazySend: IO[Stream[IO, WebSocketFrame.Text]] = findById(chatTopicsRef)(chatId).map {
          case Some(chat) => chat.subscribe(10).collect { case o: Out => Response(o).asJson.asText }
          case _          => Stream.empty
        }

        val receive: Pipe[IO, WebSocketFrame, Unit] = stream => Stream.eval(lazyReceive).flatMap(_(stream))
        val send: Stream[IO, WebSocketFrame] = Stream.eval(lazySend).flatten

        wsb.build(send, receive)

    }
  }.orNotFound
}
