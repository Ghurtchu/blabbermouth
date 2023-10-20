import cats.effect.{ExitCode, IO, IOApp, Ref}
import com.comcast.ip4s.IpLiteralSyntax
import fs2.{Pipe, Stream}
import fs2.concurrent.Topic
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import play.api.libs.json.Format.GenericFormat
import play.api.libs.json.{Format, JsObject, JsResult, JsString, JsValue, Json, Reads, Writes}

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

object Main extends IOApp.Simple {

  /** Live support chat app
    *
    * Functionality:
    *   - any user can join the chat app and talk with the support
    *   - input your name
    *   - get queued
    *   - support notices you
    *   - joins your chat
    *   - you talk shit with him/her
    */

  final case class ChatMetaData(chatId: String, supportId: Option[String])

  type UserId              = String
  type CachedChatMetaData  = Map[UserId, ChatMetaData]
  type CachedConversations = Map[UserId, Conversation]

  type TopicsCache = Map[ChatId, Topic[IO, Message]]

  sealed trait Message

  sealed trait In                                                                         extends Message
  sealed trait Out                                                                        extends Message
  sealed trait ChatMessage                                                                extends In with Out
  final case class MessageFromUser(userId: String, supportId: String, content: String)    extends ChatMessage
  final case class MessageFromSupport(supportId: String, userId: String, content: String) extends ChatMessage
  final case class Conversation(messages: Vector[ChatMessage]) {
    def add(msg: ChatMessage): Conversation =
      copy(messages :+ msg)
  }
  object Conversation                                          {
    def empty: Conversation = Conversation(Vector.empty)
  }
  final case class Connect(username: String) extends In
  final case class SupportJoin(username: String, userId: String, chatId: String, supportId: Option[String])
      extends In
  final case class UserJoin(username: String, userId: String, supportId: Option[String], chatId: String)
      extends In
  final case class Connected(userId: String, username: String, chatId: String) extends Out
  final case class SupportJoined(supportId: String, supportUsername: String, userId: String, chatId: String)
      extends Out
  final case class UserJoined(userId: String, chatId: String)                  extends Out

  implicit val chatMsg: Format[ChatMessage] = new Format[ChatMessage] {
    override def reads(json: JsValue): JsResult[ChatMessage] =
      msgFromUserFmt.reads(json) orElse
        msgFromSupportFmt.reads(json)

    override def writes(o: ChatMessage): JsValue = o match {
      case mfc: MessageFromUser    => msgFromUserFmt.writes(mfc)
      case mfs: MessageFromSupport => msgFromSupportFmt.writes(mfs)
    }
  }

  implicit val msgFromUserFmt: Format[MessageFromUser]       = Json.format[MessageFromUser]
  implicit val msgFromSupportFmt: Format[MessageFromSupport] = Json.format[MessageFromSupport]
  implicit val conversationFmt: Format[Conversation]         = Json.format[Conversation]
  implicit val connectFmt: Format[Connect]                   = Json.format[Connect]
  implicit val supportJoinFmt: Format[SupportJoin]           = Json.format[SupportJoin]
  implicit val userJoinFmt: Format[UserJoin]                 = Json.format[UserJoin]
  implicit val connectedFmt: Format[Connected]               = Json.format[Connected]
  implicit val supportJoinedFmt: Format[SupportJoined]       = Json.format[SupportJoined]
  implicit val userJoinedFmt: Format[UserJoined]             = Json.format[UserJoined]

  final case class Request(requestType: String, args: In)
  final case class Response(args: Out)

  implicit val InRequestBodyFormat2: Reads[Request] = new Reads[Request] {
    override def reads(json: JsValue): JsResult[Request] =
      for {
        requestType <- (json \ "requestType").validate[String]
        args        <- (json \ "args").validate[JsValue]
        in          <- requestType match {
          case "UserJoin"           => userJoinFmt.reads(args)
          case "Connect"            => connectFmt.reads(args)
          case "SupportJoin"        => supportJoinFmt.reads(args)
          case "MessageFromUser"    => msgFromUserFmt.reads(args)
          case "MessageFromSupport" => msgFromSupportFmt.reads(args)
        }
      } yield Request(requestType, in)
  }

  implicit val outFormat: Writes[Out] = {
    case m: ChatMessage   => chatMsg.writes(m)
    case c: Connected     => connectedFmt.writes(c)
    case s: SupportJoined => supportJoinedFmt.writes(s)
    case u: UserJoined    => userJoinedFmt.writes(u)
  }

  implicit val OutRequestBodyFormat: Writes[Response] = new Writes[Response] {
    override def writes(o: Response): JsValue = {
      val requestType = o.args.getClass.getSimpleName
      JsObject(
        Map(
          "requestType" -> JsString(requestType),
          "args"        -> outFormat.writes(o.args),
        ),
      )
    }
  }

  type ChatId = String

  val run = for {
    topics      <- IO.ref(Map.empty[String, Topic[IO, Message]]) // ChatId -> Topic[IO, Message]
    userToChat  <- IO.ref(Map.empty[UserId, ChatId])             // ChatId -> CustomerId
    userToConvo <- IO.ref(Map.empty[UserId, Conversation])
    _           <- EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"9000")
      .withHttpWebSocketApp(httpApp(_, topics, userToChat, userToConvo))
      .withIdleTimeout(120.seconds)
      .build
      .useForever
  } yield ExitCode.Success

  private def httpApp(
    wsb: WebSocketBuilder2[IO],
    topics: Ref[IO, TopicsCache],
    userIdToChatId: Ref[IO, Map[UserId, ChatId]],
    customerIdToConversation: Ref[IO, Map[UserId, Conversation]],
  ): HttpApp[IO] = {
    val dsl = new Http4sDsl[IO] {}
    import dsl._
    HttpRoutes.of[IO] {

      // generates chat id for customer
      case GET -> Root / "user" / "join" / username =>
        for {
          userId   <- IO(java.util.UUID.randomUUID().toString.replaceAll("-", ""))
          chatId   <- IO(java.util.UUID.randomUUID().toString.replaceAll("-", ""))
          _        <- userIdToChatId.getAndUpdate(_.updated(userId, chatId))
          _        <- IO.println("ChatsRef:")
          _        <- userIdToChatId.get.flatTap(IO.println)
          topic    <- Topic[IO, Message]
          _        <- topics.getAndUpdate(_.updated(chatId, topic))
          _        <- IO.println("TopicsRef:")
          _        <- topics.get.flatTap(IO.println)
          response <- Ok(Json.prettyPrint(Json.toJson(Connected(userId, username, chatId))))
        } yield response

      case GET -> Root / "chat" / chatId =>
        val findTopicById: String => IO[Topic[IO, Message]] = chatId =>
          topics.get.flatMap {
            _.get(chatId)
              .map(IO.pure)
              .getOrElse(IO.raiseError(new RuntimeException(s"Chat with $chatId does not exist")))
          }

        val lazyReceive: IO[Pipe[IO, WebSocketFrame, Unit]] =
          for {
            topic <- findTopicById(chatId)
          } yield topic.publish
            .compose[Stream[IO, WebSocketFrame]](_.collect { case text: WebSocketFrame.Text =>
              println(text.str)
              Json
                .parse(text.str)
                .as[Request]
                .args match {
                // User joins for the first time
                case UserJoin(username, userId, None, chatId)    =>
                  println(s"[USER]$username is trying to connect")
                  UserJoined(userId, chatId)

                // Support joins for the first time
                case SupportJoin(username, userId, chatId, None) =>
                  println(s"[SUPPORT] $username is trying to connect")
                  val supportId = java.util.UUID.randomUUID().toString.replaceAll("-", "")

                  SupportJoined(supportId, username, userId, chatId)

                case mfu: MessageFromUser => mfu

                case mfs: MessageFromSupport => mfs

              }
            })

        // Stream[IO, WebSocketFrame] => Stream[IO, Unit]
        val receive: Pipe[IO, WebSocketFrame, Unit] =
          (input: Stream[IO, WebSocketFrame]) =>
            Stream
              .eval(lazyReceive)
              .flatMap(_(input))

        val lazySend: IO[Stream[IO, WebSocketFrame]] =
          for {
            topic <- findTopicById(chatId)
          } yield topic
            .subscribe(10)
            .collect { case out: Out => WebSocketFrame.Text(Json.prettyPrint(Json.toJson(Response(out)))) }

        val send: Stream[IO, WebSocketFrame] = Stream.eval(lazySend).flatten

        wsb.build(send, receive)
    }
  }.orNotFound
}
