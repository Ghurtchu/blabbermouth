import cats.effect.{ExitCode, IO, IOApp, Ref, Resource}
import com.comcast.ip4s.IpLiteralSyntax
import fs2.{Pipe, Stream}
import fs2.concurrent.Topic
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import play.api.libs.json._

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.DurationInt

object Main extends IOApp.Simple {

  type UserId = String
  type ChatId = String

  sealed trait Message

  sealed trait In  extends Message
  sealed trait Out extends Message

  sealed trait Participant {
    override def toString: String = this.getClass.getSimpleName.init // drops dollar sign
  }

  object Participant {
    def fromString: String => Option[Participant] = PartialFunction.condOpt(_) {
      case "User"    => User
      case "Support" => Support
    }
  }

  case object User    extends Participant
  case object Support extends Participant

  private def participantStrToJsResult: String => JsResult[Participant] =
    Participant.fromString(_).map(JsSuccess(_)).getOrElse(JsError("incorrect `from` value"))

  implicit val participantFmt: Format[Participant] = new Format[Participant] {
    override def writes(o: Participant): JsValue             = JsString(o.toString)
    override def reads(json: JsValue): JsResult[Participant] = for {
      from <- (json \ "from").validate[String].flatMap(participantStrToJsResult)
    } yield from
  }

  final case class ChatMessage(
    userId: String,
    supportId: String,
    content: String,
    timestamp: Instant,
    from: Participant,
  ) extends In
      with Out

  implicit val chatMsgFmt: Format[ChatMessage] = new Format[ChatMessage] {
    override def reads(json: JsValue): JsResult[ChatMessage] = for {
      userId    <- (json \ "userId").validate[String]
      supportId <- (json \ "supportId").validate[String]
      content   <- (json \ "content").validate[String]
      from      <- participantFmt.reads(json)
    } yield ChatMessage(userId, supportId, content, Instant.now(), from)
    override def writes(o: ChatMessage): JsValue             = Json.writes[ChatMessage].writes(o)
  }

  final case class Join(
    from: Participant,
    userId: String,
    username: String,
    chatId: String,
    supportId: Option[String],
    supportUserName: Option[String],
  ) extends In

  implicit val readsJoin: Reads[Join] = json =>
    for {
      from            <- (json \ "from").validate[String].flatMap(participantStrToJsResult)
      userId          <- (json \ "userId").validate[String]
      username        <- (json \ "username").validate[String]
      chatId          <- (json \ "chatId").validate[String]
      supportId       <- (json \ "supportId").validateOpt[String]
      supportUserName <- (json \ "supportUserName").validateOpt[String]
    } yield Join(from, userId, username, chatId, supportId, supportUserName)

  implicit val writesJoin: Writes[Join] = Json.writes[Join]

  final case class Joined(
    participant: Participant,
    userId: String,
    chatId: String,
    supportId: Option[String],
    supportUserName: Option[String],
  ) extends Out

  implicit val joinedFmt: Format[Joined] = Json.format[Joined]
  final case class Connected(userId: String, username: String, chatId: String) extends Out
  final case class Request(`type`: String, args: In)
  final case class Response(args: Out)

  final case class ChatHistory(messages: Vector[ChatMessage]) extends Out {
    def +(msg: ChatMessage): ChatHistory = copy(messages :+ msg)
  }

  implicit val chatHistoryFmt: Format[ChatHistory] = Json.format[ChatHistory]
  implicit val connectedFmt: Format[Connected]     = Json.format[Connected]
  implicit val requestFmt: Reads[Request]          = json =>
    for {
      typ  <- (json \ "type").validate[String]
      args <- (json \ "args").validate[JsValue]
      in   <- typ match {
        case "Join"        => readsJoin.reads(args)
        case "ChatMessage" => chatMsgFmt.reads(args)
      }
    } yield Request(typ, in)
  implicit val outFmt: Writes[Out]                 = {
    case m: ChatMessage => chatMsgFmt.writes(m)
    case c: Connected   => connectedFmt.writes(c)
    case c: ChatHistory => chatHistoryFmt.writes(c)
    case j: Joined      => joinedFmt.writes(j)
    case c: ChatExpired => chatExpiredFmt.writes(c)
  }
  implicit val responseFmt: Writes[Response]       = response => {
    val `type` = response.args.getClass.getSimpleName
    JsObject(Map("type" -> JsString(`type`), "args" -> outFmt.writes(response.args)))
  }

  final case class ChatExpired(chatId: String) extends Out

  implicit val chatExpiredFmt: Format[ChatExpired] = Json.format[ChatExpired]

  private def generateRandomId: IO[String] = IO(java.util.UUID.randomUUID().toString.replaceAll("-", ""))

  val run = (for {
    topics    <- Resource.eval(IO.ref(Map.empty[String, Topic[IO, Message]])) // ChatId -> Topic[IO, Message])
    userChats <- Resource.eval(IO.ref(Map.empty[UserId, ChatId]))             // ChatId -> CustomerId
    chatHistory <- Resource.eval(IO.ref(Map.empty[UserId, ChatHistory]))
    _           <- EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"9000")
      .withHttpWebSocketApp(httpApp(_, topics, userChats, chatHistory))
      .withIdleTimeout(120.seconds)
      .build
    _           <- (for {
      _ <- chatHistory.getAndUpdate {
        _.flatMap { case (userId, history) =>
          history.messages.lastOption.fold(Option(userId -> history)) { msg =>
            val difference = ChronoUnit.MINUTES.between(msg.timestamp, Instant.now())
            Option.when(difference < 2)(userId -> history)
          }
        }
      }
    } yield ()).flatMap(_ => IO.sleep(30.seconds)).foreverM.toResource
  } yield ExitCode.Success).useForever

  implicit class AsJson[A: Writes](self: A) {
    def asJson: String = Json.prettyPrint(Json.toJson(self))
  }

  def findById[A](cache: Ref[IO, Map[String, A]])(id: String): IO[Option[A]] =
    cache.get.flatMap(c => IO(c.get(id)))

  private def httpApp(
    wsb: WebSocketBuilder2[IO],
    topics: Ref[IO, Map[ChatId, Topic[IO, Message]]],
    userChats: Ref[IO, Map[UserId, ChatId]],
    chatHistory: Ref[IO, Map[ChatId, ChatHistory]],
  ): HttpApp[IO] = {
    val dsl = new Http4sDsl[IO] {}
    import dsl._
    HttpRoutes.of[IO] {
      case GET -> Root / "user" / "join" / username =>
        IO.both(generateRandomId, generateRandomId).flatMap { case (userId, chatId) =>
          for {
            _        <- userChats.getAndUpdate(_.updated(userId, chatId))
            topic    <- Topic[IO, Message]
            _        <- topics.getAndUpdate(_.updated(chatId, topic))
            response <- Ok(Connected(userId, username, chatId).asJson)
          } yield response
        }
      case GET -> Root / "chat" / chatId            =>
        val lazyReceive: IO[Pipe[IO, WebSocketFrame, Unit]] =
          findById(topics)(chatId).map {
            case Some(topic) =>
              topic.publish.compose[Stream[IO, WebSocketFrame]](_.evalMap {
                case WebSocketFrame.Text(body, _) =>
                  Json.parse(body).as[Request].args match {
                    // User joins for the first time
                    case Join(u @ User, userId, _, _, None, None) => IO(Joined(u, userId, chatId, None, None))
                    // User re-joins (browser refresh), so we load chat history
                    case Join(User, _, _, _, Some(_), _)          =>
                      findById(chatHistory)(chatId).map(_.fold[Out](ChatExpired(chatId))(identity))
                    // Support joins for the first time
                    case Join(s @ Support, userId, _, chatId, None, u @ Some(_)) =>
                      for {
                        supportId <- generateRandomId
                        _         <- chatHistory.getAndUpdate(_.updated(chatId, ChatHistory(Vector.empty)))
                      } yield Joined(s, userId, chatId, Some(supportId), u)
                    // Support re-joins (browser refresh), so we load chat history
                    case Join(Support, _, _, _, Some(_), Some(_))                =>
                      findById(chatHistory)(chatId).map(_.fold[Out](ChatExpired(chatId))(identity))
                    // chat message either from user or support
                    case msg: ChatMessage                                        =>
                      findById(chatHistory)(chatId).flatMap {
                        case Some(history) =>
                          for {
                            _ <- chatHistory.getAndUpdate(_.updated(chatId, history + msg))
                          } yield msg
                        case None          => IO(ChatExpired(chatId))
                      }
                  }
              })
            case None        => (_: Stream[IO, WebSocketFrame]) => Stream.empty
          }

        val lazySend: IO[Stream[IO, WebSocketFrame]] = findById(topics)(chatId).map {
          case Some(topic) =>
            topic.subscribe(10).collect { case out: Out =>
              WebSocketFrame.Text(Response(out).asJson)
            }
          case None        => Stream.empty
        }

        val receive: Pipe[IO, WebSocketFrame, Unit] = stream => Stream.eval(lazyReceive).flatMap(_(stream))
        val send: Stream[IO, WebSocketFrame]        = Stream.eval(lazySend).flatten

        wsb.build(send, receive)
    }
  }.orNotFound
}
