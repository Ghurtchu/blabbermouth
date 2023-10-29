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

  sealed trait Participant {
    override def toString: String = this.getClass.getSimpleName.init // drops dollar sign
  }
  case object User extends Participant
  case object Support extends Participant
  object Participant {
    def fromString: String => Option[Participant] = PartialFunction.condOpt(_) {
      case "User"    => User
      case "Support" => Support
    }
  }
  private def toParticipant: String => JsResult[Participant] =
    Participant.fromString(_).map(JsSuccess(_)).getOrElse(JsError("unrecognized participant value"))

  implicit val writesParticipant: Writes[Participant] = p => JsString(p.toString)
  implicit val readsParticipant: Reads[Participant] = _.validate[String].flatMap(toParticipant)

  case class ChatMessage(
    content: String,
    from: Participant,
    userId: String,
    supportId: String,
    timestamp: Option[Instant] = None,
  ) extends In
      with Out
  implicit val chatMsgFmt: Format[ChatMessage] = Json.format[ChatMessage]

  case class Join(
    from: Participant,
    userId: String,
    username: String,
    supportId: Option[String],
    supportUserName: Option[String],
  ) extends In
  implicit val readsJoin: Reads[Join] = Json.reads[Join]

  case class Joined(
    participant: Participant,
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

  def generateRandomId: IO[String] = IO(java.util.UUID.randomUUID().toString.replaceAll("-", ""))

  val redisResource: Resource[IO, SortedSetCommands[IO, String, String]] =
    RedisClient[IO]
      .from("redis://redis")
      .flatMap(Redis[IO].fromClient(_, RedisCodec.Utf8))

  val run = (for {
    redisStream <- RedisClient[IO]
      .from("redis://redis")
      .flatMap(PubSub.mkPubSubConnection[IO, String, String](_, RedisCodec.Utf8).map(_.publish(RedisChannel("joins"))))
    chatTopics <- Resource.eval(IO.ref(Map.empty[ChatId, ChatTopic]))
    chatHistory <- Resource.eval(IO.ref(Map.empty[UserId, ChatHistory]))
    _ <- EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"9000")
      .withHttpWebSocketApp(webSocketApp(_, chatTopics, chatHistory, redisStream))
      .withIdleTimeout(120.seconds)
      .build
    _ <- (for {
      now <- IO.realTimeInstant
      _ <- IO.println(s"Running cache cleanup for ChatHistory: $now")
      _ <- chatHistory.getAndUpdate {
        _.flatMap { case (userId, history) =>
          history.messages.lastOption
            .fold(Option(userId -> history)) {
              _.timestamp.flatMap { msgTimestamp =>
                val diffInMinutes = ChronoUnit.MINUTES.between(msgTimestamp, now)
                Option.when(diffInMinutes < 2)(userId -> history)
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
    chatTopics: Ref[IO, Map[ChatId, ChatTopic]],
    chatHistory: Ref[IO, Map[ChatId, ChatHistory]],
    redisStream: Stream[IO, String] => Stream[IO, Unit],
  ): HttpApp[IO] = {
    val dsl = new Http4sDsl[IO] {}
    import dsl._
    HttpRoutes.of[IO] {
      case GET -> Root / "user" / "join" / username =>
        IO.both(generateRandomId, generateRandomId).flatMap { case (userId, chatId) =>
          for {
            _ <- IO.println(s"Registering $username ...")
            _ <- chatHistory.getAndUpdate(_.updated(chatId, ChatHistory.init(chatId)))
            _ <- chatHistory.get.flatTap(cache => IO.println(s"ChatHistory cache: $cache"))
            chatTopic <- Topic[IO, Message]
            _ <- chatTopics.getAndUpdate(_.updated(chatId, chatTopic))
            _ <- IO.println(s"Registered $username")
            response <- Ok(Registered(userId, username, chatId).asJson)
          } yield response
        }
      case GET -> Root / "chat" / chatId =>
        val lazyReceive: IO[Pipe[IO, WebSocketFrame, Unit]] =
          findById(chatTopics)(chatId).map {
            case Some(chat) =>
              chat.publish.compose[Stream[IO, WebSocketFrame]](_.evalMap { case WebSocketFrame.Text(body, _) =>
                println(s"received message: $body")
                Json.parse(body).as[Request].args match {
                  // User joins for the first time
                  case Join(u @ User, userId, un, None, None) =>
                    for {
                      joined <- IO.pure(Joined(u, userId, chatId, None, None))
                      json = joined.asJson
                      pubSubJson = s"""{"type":"UserJoined","args":$json}"""
                      _ <- IO.println(s"Writing $json into Redis with Score 0 - pending")
                      _ <- redisResource.use(_.zAdd("users", None, ScoreWithValue(Score(0), json)))
                      _ <- IO.println(s"Publishing $pubSubJson into Redis pub/sub for Support UI")
                      _ <- Stream.emit(pubSubJson).through(redisStream).compile.foldMonoid
                    } yield joined
                  // User re-joins (browser refresh), so we load chat history
                  case Join(User, _, _, Some(_), _) => findById(chatHistory)(chatId).map(_.getOrElse(ChatExpired(chatId)))
                  // Support joins for the first time
                  case Join(s @ Support, userId, _, None, u @ Some(_)) =>
                    for {
                      _ <- IO.println(s"Support attempting to join user with userId: $userId")
                      joined <- generateRandomId.map(id => Joined(s, userId, chatId, Some(id), u))
                      _ <- IO.println(s"Support joined the user with userId: $userId")
                    } yield joined
                  // Support re-joins (browser refresh), so we load chat history
                  case Join(Support, _, _, Some(_), Some(_)) => findById(chatHistory)(chatId).map(_.getOrElse(ChatExpired(chatId)))
                  // chat message either from user or support
                  case msg: ChatMessage =>
                    for {
                      now <- IO.realTimeInstant
                      _ <- IO.println(s"${msg.from} sent message=${msg.content}, timestamp: $now")
                      msgWithTimestamp = msg.copy(timestamp = Some(now))
                      _ <- findById(chatHistory)(chatId).flatMap {
                        case Some(hist) =>
                          chatHistory
                            .getAndUpdate(_.updated(chatId, hist + msgWithTimestamp))
                            .as(msgWithTimestamp)
                            .flatTap(_ => IO.println("ChatHistory was updated"))
                        case None => IO.pure(ChatExpired(chatId)).flatTap(ce => IO.println(s"Chat was expired: $ce"))
                      }
                    } yield msg
                }
              })
            case _ => (_: Stream[IO, WebSocketFrame]) => Stream.empty
          }

        val lazySend: IO[Stream[IO, WebSocketFrame.Text]] = findById(chatTopics)(chatId).map {
          case Some(chat) => chat.subscribe(10).collect { case o: Out => Response(o).asJson.asText }
          case _          => Stream.empty
        }

        val receive: Pipe[IO, WebSocketFrame, Unit] = stream => Stream.eval(lazyReceive).flatMap(_(stream))
        val send: Stream[IO, WebSocketFrame] = Stream.eval(lazySend).flatten

        wsb.build(send, receive)
    }
  }.orNotFound
}