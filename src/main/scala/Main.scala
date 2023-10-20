import cats.effect.std.Queue
import cats.effect.{ExitCode, IO, IOApp, Ref}
import com.comcast.ip4s.IpLiteralSyntax
import fs2.{Pipe, Stream}
import fs2.concurrent.Topic
import io.circe.Decoder.Result
import io.circe.{Decoder, DecodingFailure, HCursor}
import org.http4s.circe.jsonOf
import org.http4s.dsl.Http4sDsl
import org.http4s.{EntityDecoder, HttpApp, HttpRoutes}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import play.api.libs.json.Format.GenericFormat
import play.api.libs.json.{
  Format,
  JsArray,
  JsBoolean,
  JsError,
  JsNull,
  JsNumber,
  JsObject,
  JsResult,
  JsString,
  JsSuccess,
  JsValue,
  Json,
  Reads,
}

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}
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

  type TopicsCache = Map[UserId, Topic[IO, Message]]

  sealed trait Message

  sealed trait In  extends Message
  sealed trait Out extends Message

  sealed trait FromUser    extends In
  sealed trait FromSupport extends In

  sealed trait ToUser    extends Out
  sealed trait ToSupport extends Out

  sealed trait ChatMessage extends In with Out

  final case class MessageFromUser(
    userId: String,
    supportId: String,
    content: String,
  ) extends ChatMessage
      with FromUser
      with ToSupport

  final case class MessageFromSupport(
    supportId: String,
    userId: String,
    content: String,
  ) extends ChatMessage
      with FromSupport
      with ToUser

  final case class Conversation(messages: Vector[ChatMessage]) extends ToUser with ToSupport {
    def add(msg: ChatMessage): Conversation =
      copy(messages :+ msg)
  }

  object Conversation {
    def empty: Conversation = Conversation(Vector.empty)
  }

  final case class Connect(username: String) extends FromUser
  final case class Disconnect(id: String)    extends FromUser

  final case class SupportJoin(
    username: String,
    userId: String,
    chatId: String,
    supportId: Option[String],
  ) extends In

  final case class UserJoin(
    username: String,
    userId: String,
    supportId: Option[String],
    chatId: String,
  ) extends In

  final case class Connected(
    userId: String,
    username: String,
  ) extends ToUser

  final case class SupportJoined(
    supportId: String,
    supportUsername: String,
    userId: String,
    chatId: String,
  ) extends ToSupport

  final case class UserJoined(
    userId: String,
    chatId: String,
  ) extends ToSupport

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

  implicit val conversationFmt: Format[Conversation] = Json.format[Conversation]

  implicit val connectFmt: Format[Connect]         = Json.format[Connect]
  implicit val supportJoinFmt: Format[SupportJoin] = Json.format[SupportJoin]
  implicit val userJoinFmt: Format[UserJoin]       = Json.format[UserJoin]
  implicit val disconnectFmt: Format[Disconnect]   = Json.format[Disconnect]

  implicit val connectedFmt: Format[Connected]         = Json.format[Connected]
  implicit val supportJoinedFmt: Format[SupportJoined] = Json.format[SupportJoined]
  implicit val userJoinedFmt: Format[UserJoined]       = Json.format[UserJoined]

  final case class RequestBody(`type`: String, args: In)

  implicit val RequestBodyFormat: Reads[RequestBody] = new Reads[RequestBody] {
    override def reads(json: JsValue): JsResult[RequestBody] =
      (json \ "type").validate[String] flatMap {
        case m @ "MessageFromUser"    =>
          msgFromUserFmt
            .reads(json("args"))
            .map(RequestBody(m, _))
        case s @ "MessageFromSupport" =>
          msgFromSupportFmt
            .reads(json("args"))
            .map(RequestBody(s, _))
        case c @ "Connect"            =>
          connectFmt
            .reads(json("args"))
            .map(RequestBody(c, _))
        case d @ "Disconnect"         =>
          disconnectFmt
            .reads(json("args"))
            .map(RequestBody(d, _))
        case j @ "SupportJoin"        =>
          supportJoinFmt
            .reads(json("args"))
            .map(RequestBody(j, _))
        case c @ "UserJoin"           =>
          userJoinFmt
            .reads(json("args"))
            .map(RequestBody(c, _))
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
      .withHttpWebSocketApp(
        httpApp(_, topics, userToChat, userToConvo),
      )
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
          result   <- IO.pure {
            val userId = java.util.UUID.randomUUID().toString.replaceAll("-", "")
            val chatId = java.util.UUID.randomUUID().toString.replaceAll("-", "")

            (userId, chatId)
          }
          _        <- userIdToChatId.getAndUpdate(_.updated(result._1, result._2))
          _        <- IO.println("ChatsRef:")
          _        <- userIdToChatId.get.flatTap(IO.println)
          topic    <- Topic[IO, Message]
          _        <- topics.getAndUpdate(_.updated(result._2, topic))
          _        <- IO.println("TopicsRef:")
          _        <- topics.get.flatTap(IO.println)
          response <- Ok {
            s"""{
              |  "username": "$username",
              |  "userId": "${result._1}",
              |  "chatId": "${result._2}"
              |}""".stripMargin
          }
        } yield response

      case chatReq @ GET -> Root / "chat" / chatId =>
        val findTopicById: String => IO[Topic[IO, Message]] = chatId =>
          topics.get.flatMap {
            _.get(chatId)
              .map(IO.pure)
              .getOrElse(IO.raiseError(new RuntimeException(s"Chat with $chatId does not exist")))
          }

        val rec: Pipe[IO, WebSocketFrame, Unit] = _.evalMap { case text: WebSocketFrame.Text =>
          println("line 252")
          println(text.str)
          val body = Try(Json.parse(text.str.trim).as[RequestBody])
          body match {
            case Failure(exception) => IO.unit
            case Success(value)     =>
              value.args match {
                case UserJoin(username, customerId, supportId, chatId) =>
                  val d = for {
                    _     <- IO.println("resp")
                    topic <- findTopicById("1")
                    _     <- IO.println("respaaaa")
                    _     <- topic.publish1(UserJoin(username, customerId, supportId, chatId))
                  } yield ()

                  d
              }
          }
        }

//          val lazyReceive: IO[Pipe[IO, WebSocketFrame, Unit]] = for {
//              _    <- IO.println("here???????")
//              chatId <- chatReq.as[RequestBody].onError(IO.println)
//              _    <- IO.println(chatId)
////              chatId <- IO.pure(body.args match {
////                case SupportJoin(_, _, chatId, _) => chatId
////                case CustomerJoin(_, _, _, chatId) => chatId
////              })
//              _ <- IO.println("for real dude?")
//              topic <- findTopic(chatId.args.asInstanceOf[CustomerJoin].chatId)
//              _     <- IO.println(s"topic: $topic")
//            } yield {
//             val str: Pipe[IO, WebSocketFrame, Unit] = _.collect {
//               case text: WebSocketFrame.Text =>
//                 println("here")
//                 val body = Json.parse(text.str.trim).as[RequestBody]
//                 println(body)
//                 body.args match {
//                   case customer: FromCustomer => ???
//                   case support: FromSupport => ???
//                   case message: ChatMessage => ???
//                   case SupportJoin(username, customerId, chatId, supportId) => ???
//                   // first join customer
//                   case CustomerJoin(username, customerId, None, chatId) => {
//                     val d: Stream[IO, Message] => Stream[IO, Unit] = topic.publish.compose[Stream[IO, Main.Message]] { _.map(identity) }
//                     for {
//                       _ <- IO.println("...")
//                       a <- IO(d)
//                     } yield a
//
//                   }
//                 }
//             }
//
//                 str
//          }

//          val receive: Pipe[IO, WebSocketFrame, Unit] = (input: Stream[IO, WebSocketFrame]) =>
//            Stream.eval(lazyReceive).flatMap(pipe => pipe(input))

        val lazyReceive: IO[Pipe[IO, WebSocketFrame, Unit]] =
          for {
            topic <- findTopicById(chatId).flatTap(IO.println)
          } yield {
            topic.publish.compose[Stream[IO, WebSocketFrame]](_.collect {
              case WebSocketFrame.Text(str, bool) =>
                println("315")
                val args = Json.parse(str).as[RequestBody].args
                args match {
                  case UserJoin(_, userId, _, chatId) =>
                    println("mdzgneri mejvia")
                    UserJoined(userId, chatId)
                }
            })
          }

        val receive: Pipe[IO, WebSocketFrame, Unit] =
          (input: Stream[IO, WebSocketFrame]) => Stream.eval(lazyReceive).flatMap(pipe => pipe(input))

        val lazySend: IO[Stream[IO, WebSocketFrame]] =
          for {
            topic <- findTopicById(chatId)
          } yield {
            val sendStream: Stream[IO, WebSocketFrame] =
              topic.subscribe(10).collect { case out: Out =>
                out match {
                  case UserJoined(userId, chatId) =>
                    WebSocketFrame.Text("raia raia")
                }
              }

            sendStream
          }

        val send: Stream[IO, WebSocketFrame] = Stream.eval(lazySend).flatten

        wsb.build(send, receive)
    }
  }.orNotFound
}
