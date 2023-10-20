import cats.effect.{ExitCode, IO, IOApp, Ref, Resource}
import com.comcast.ip4s.IpLiteralSyntax
import fs2.{Pipe, Pure, Stream}
import fs2.concurrent.Topic
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import play.api.libs.json.Format.GenericFormat
import play.api.libs.json.{Format, JsArray, JsObject, JsResult, JsString, JsValue, Json, Reads, Writes}

import java.time.Instant
import scala.concurrent.duration.DurationInt

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
    *
    * Nice to have:
    *   - cache invalidation after 2 mins of inactivity (clean up the memory) \-
    */

  type UserId      = String
  type TopicsCache = Map[ChatId, Topic[IO, Message]]

  sealed trait Message
  sealed trait In                                                              extends Message
  sealed trait Out                                                             extends Message
  sealed trait ChatMessage                                                     extends In with Out
  final case class MessageFromUser(
    userId: String,
    supportId: String,
    content: String,
    timestamp: Instant,
  ) extends ChatMessage
  final case class MessageFromSupport(
    supportId: String,
    userId: String,
    content: String,
    timestamp: Instant,
  ) extends ChatMessage
  final case class Conversation(messages: Stream[Pure, ChatMessage])           extends Out {
    def add(msg: ChatMessage): Conversation =
      copy(messages ++ Stream.emit(msg))
  }
  object Conversation {
    def empty: Conversation = Conversation(Stream.empty)
  }
  final case class Connect(username: String)                                   extends In
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

  implicit val msgFromUserFmt: Format[MessageFromUser] = new Format[MessageFromUser] {
    override def reads(json: JsValue): JsResult[MessageFromUser] =
      for {
        userId    <- (json \ "userId").validate[String]
        supportId <- (json \ "supportId").validate[String]
        content   <- (json \ "content").validate[String]
      } yield MessageFromUser(
        userId = userId,
        supportId = supportId,
        content = content,
        timestamp = Instant.now(),
      )

    override def writes(o: MessageFromUser): JsValue = Json.writes[MessageFromUser].writes(o)
  }

  implicit val msgFromSupportFmt: Format[MessageFromSupport] = new Format[MessageFromSupport] {
    override def reads(json: JsValue): JsResult[MessageFromSupport] =
      for {
        userId    <- (json \ "userId").validate[String]
        supportId <- (json \ "supportId").validate[String]
        content   <- (json \ "content").validate[String]
      } yield MessageFromSupport(
        userId = userId,
        supportId = supportId,
        content = content,
        timestamp = Instant.now(),
      )

    override def writes(o: MessageFromSupport): JsValue = Json.writes[MessageFromSupport].writes(o)
  }

  implicit val conversationFmt: Writes[Conversation] = new Writes[Conversation] {
    override def writes(o: Conversation): JsValue = {
      val jsObjects: Seq[JsObject] = o.messages.map { msg =>
        chatMsg.writes(msg) match {
          case js: JsObject => js + ("type" -> JsString(msg.getClass.getSimpleName))
        }
      }.toVector

      JsObject(Map("messages" -> JsArray(jsObjects)))
    }
  }

  implicit val connectFmt: Format[Connect]             = Json.format[Connect]
  implicit val supportJoinFmt: Format[SupportJoin]     = Json.format[SupportJoin]
  implicit val userJoinFmt: Format[UserJoin]           = Json.format[UserJoin]
  implicit val connectedFmt: Format[Connected]         = Json.format[Connected]
  implicit val supportJoinedFmt: Format[SupportJoined] = Json.format[SupportJoined]
  implicit val userJoinedFmt: Format[UserJoined]       = Json.format[UserJoined]

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
    case c: Conversation  => conversationFmt.writes(c)
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

  val run = (for {
    topics <- Resource.eval(IO.ref(Map.empty[String, Topic[IO, Message]])) // ChatId -> Topic[IO, Message])
    userToChat  <- Resource.eval(IO.ref(Map.empty[UserId, ChatId])) // ChatId -> CustomerId
    userToConvo <- Resource.eval(IO.ref(Map.empty[UserId, Conversation]))
    _           <- EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"9000")
      .withHttpWebSocketApp(httpApp(_, topics, userToChat, userToConvo))
      .withIdleTimeout(120.seconds)
      .build

    _ <- IO {
      // code for cleaning up caches

      println("yeyyy")
    }.flatMap(_ => IO.sleep(30.seconds)).foreverM.toResource
  } yield ExitCode.Success).useForever

  private def httpApp(
    wsb: WebSocketBuilder2[IO],
    topics: Ref[IO, TopicsCache],
    userIdToChatId: Ref[IO, Map[UserId, ChatId]],
    chatIdToConversation: Ref[IO, Map[ChatId, Conversation]],
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
        val findTopicByChatId: String => IO[Topic[IO, Message]] = chatId =>
          topics.get.flatMap {
            _.get(chatId)
              .map(IO.pure)
              .getOrElse(IO.raiseError(new RuntimeException(s"Chat with $chatId does not exist")))
          }

        val findConvoByChatId: String => IO[Conversation] = chatId =>
          chatIdToConversation.get.flatMap {
            _.get(chatId)
              .map { c =>
                println("found chat")
                IO.pure(c)
              }
              .getOrElse {
                println("couldn't find chat, raising error")
                IO.raiseError(new RuntimeException(s"Conversation with $chatId does not exist"))
              }
          }

        val lazyReceive: IO[Pipe[IO, WebSocketFrame, Unit]] =
          for {
            topic <- findTopicByChatId(chatId)
          } yield topic.publish
            .compose[Stream[IO, WebSocketFrame]](_.evalMap { case text: WebSocketFrame.Text =>
              Json.parse(text.str).as[Request].args match {
                // User joins for the first time
                case UserJoin(username, userId, None, chatId) =>
                  println(s"[USER]$username is trying to connect")

                  IO.pure(UserJoined(userId, chatId))

                // User re-joins (browser refresh)
                case u: UserJoin if u.supportId.isDefined     => findConvoByChatId(chatId)

                // Support joins for the first time
                case SupportJoin(username, userId, chatId, None) =>
                  println(s"[SUPPORT] $username is trying to connect")
                  val supportId = java.util.UUID.randomUUID().toString.replaceAll("-", "")

                  for {
                    _ <- chatIdToConversation.getAndUpdate(_.updated(chatId, Conversation.empty))
                    _ <- IO.println(s"initialized empty chat for $chatId")
                  } yield SupportJoined(supportId, username, userId, chatId)

                // Support re-joins (browser refresh)
                case sj: SupportJoin if sj.supportId.isDefined   => findConvoByChatId(chatId)

                case mfu: MessageFromUser =>
                  for {
                    convo <- findConvoByChatId(chatId)
                    _     <- chatIdToConversation.getAndUpdate(_.updated(chatId, convo.add(mfu)))
                  } yield mfu

                case mfs: MessageFromSupport =>
                  for {
                    convo <- findConvoByChatId(chatId)
                    _     <- chatIdToConversation.getAndUpdate(_.updated(chatId, convo.add(mfs)))
                  } yield mfs

              }
            })

        // Stream[IO, WebSocketFrame] => Stream[IO, Unit]
        val receive: Pipe[IO, WebSocketFrame, Unit] = stream => Stream.eval(lazyReceive).flatMap(_(stream))

        val lazySend: IO[Stream[IO, WebSocketFrame]] = findTopicByChatId(chatId).map {
          _.subscribe(10).collect { case out: Out =>
            WebSocketFrame.Text(Json.prettyPrint(Json.toJson(Response(out))))
          }
        }

        val send: Stream[IO, WebSocketFrame] = Stream.eval(lazySend).flatten

        wsb.build(send, receive)
    }
  }.orNotFound
}
