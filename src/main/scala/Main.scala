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
import play.api.libs.json.{Format, JsArray, JsBoolean, JsError, JsNull, JsNumber, JsObject, JsResult, JsString, JsSuccess, JsValue, Json, Reads}

import java.util.UUID
import scala.collection.concurrent.TrieMap

object Main extends IOApp.Simple {

  /**
   * Live support chat app
   *
   * Functionality:
   * - any user can join the chat app and talk with the support
   * - input your name
   * - get queued
   * - support notices you
   * - joins your chat
   * - you talk shit with him/her
   *
   */

  final case class ChatMetaData(chatId: String, supportId: Option[String])

  type CustomerId = String
  type CachedChatMetaData = Map[CustomerId, ChatMetaData]
  type CachedConversations = Map[CustomerId, Conversation]

  type TopicsCache = Map[CustomerId, Topic[IO, Message]]


  sealed trait Message

  sealed trait In  extends Message
  sealed trait Out extends Message

  sealed trait FromCustomer extends In
  sealed trait FromSupport  extends In

  sealed trait ToCustomer   extends Out
  sealed trait ToSupport    extends Out

  sealed trait ChatMessage extends In with Out

  final case class MessageFromCustomer(
    customerId: String,
    supportId: String,
    content: String
  ) extends ChatMessage with FromCustomer with ToSupport

  final case class MessageFromSupport(
    supportId: String,
    customerId: String,
    content: String
  ) extends ChatMessage with FromSupport with ToCustomer

  final case class Conversation(messages: Vector[ChatMessage]) extends ToCustomer with ToSupport {
    def add(msg: ChatMessage): Conversation =
      copy(messages :+ msg)
  }

  object Conversation {
    def empty: Conversation = Conversation(Vector.empty)
  }

  final case class Connect(username: String) extends FromCustomer
  final case class Disconnect(id: String)    extends FromCustomer

  final case class SupportJoin(
    username: String,
    customerId: String,
    chatId: String,
    supportId: Option[String]
  ) extends In

  final case class CustomerJoin(
    username: String,
    customerId: String,
    supportId: Option[String],
    chatId: String
  ) extends In

  final case class Connected(
    customerId: String,
    username: String
  ) extends ToCustomer

  final case class SupportJoined(
    supportId: String,
    supportUsername: String,
    customerId: String,
    chatId: String
  ) extends ToSupport

  final case class CustomerJoined(
    customerId: String,
    chatId: String
  ) extends ToSupport

  implicit val chatMsg: Format[ChatMessage] = new Format[ChatMessage] {
    override def reads(json: JsValue): JsResult[ChatMessage] =
      msgFromCustomerFmt.reads(json) orElse
        msgFromSupportFmt.reads(json)

    override def writes(o: ChatMessage): JsValue = o match {
      case mfc: MessageFromCustomer => msgFromCustomerFmt.writes(mfc)
      case mfs: MessageFromSupport => msgFromSupportFmt.writes(mfs)
    }
  }

  implicit val msgFromCustomerFmt: Format[MessageFromCustomer] = Json.format[MessageFromCustomer]
  implicit val msgFromSupportFmt: Format[MessageFromSupport] = Json.format[MessageFromSupport]

  implicit val conversationFmt: Format[Conversation] = Json.format[Conversation]

  implicit val connectFmt: Format[Connect] = Json.format[Connect]
  implicit val supportJoinFmt: Format[SupportJoin] = Json.format[SupportJoin]
  implicit val customerJoinFmt: Format[CustomerJoin] = Json.format[CustomerJoin]
  implicit val disconnectFmt: Format[Disconnect] = Json.format[Disconnect]

  implicit val connectedFmt: Format[Connected] = Json.format[Connected]
  implicit val supportJoinedFmt: Format[SupportJoined] = Json.format[SupportJoined]
  implicit val customerJoinedFmt: Format[CustomerJoined] = Json.format[CustomerJoined]

  final case class RequestBody(`type`: String, args: In)

  implicit val RequestBodyFormat: Reads[RequestBody] = new Reads[RequestBody] {
    override def reads(json: JsValue): JsResult[RequestBody] =
      (json \ "type").validate[String] flatMap {
        case m @ "MessageFromCustomer" => msgFromCustomerFmt.reads(json("args"))
          .map(RequestBody(m, _))
        case s @ "MessageFromSupport" => msgFromSupportFmt.reads(json("args"))
          .map(RequestBody(s, _))
        case c @ "Connect" => connectFmt.reads(json("args"))
          .map(RequestBody(c, _))
        case d @ "Disconnect" => disconnectFmt.reads(json("args"))
          .map(RequestBody(d, _))
        case j @ "SupportJoin" => supportJoinFmt.reads(json("args"))
          .map(RequestBody(j, _))
        case c @ "CustomerJoin" => customerJoinFmt.reads(json("args"))
          .map(RequestBody(c, _))
      }
  }

  val run = for {
    // topics
    // customer id -> topic
    topics <- IO.ref(Map.empty[CustomerId, Topic[IO, Message]])

    // chats
    // chat id -> customer id
    // cachedChatMetaData <- IO.ref(Map.empty[CustomerId, ChatMetaData])
    // cachedConvos <- IO.ref(Map.empty[CustomerId, Conversation])
    _ <- EmberServerBuilder
    .default[IO]
    .withHost(host"0.0.0.0")
    .withPort(port"8080")
    .withHttpWebSocketApp(httpApp(_, topics))
    .build
      .useForever
  } yield ExitCode.Success

  private def httpApp(
    wsb: WebSocketBuilder2[IO],
    topics: Ref[IO, TopicsCache],
  ): HttpApp[IO] = {
    val dsl = new Http4sDsl[IO] {}
    import dsl._
    HttpRoutes.of[IO] {
//      case req @ GET -> Root / "join" =>
//        for {
//          connect <- req.as[String].map(Json.parse(_).as[RequestBody].args.asInstanceOf[Connect]).flatTap(IO.println)
//          result  <- IO.delay {
//            val chatId = java.util.UUID.randomUUID().toString.replaceAll("-", "")
//            val userId = java.util.UUID.randomUUID().toString.replaceAll("-", "")
//
//            (connect, userId, chatId)
//          }
//          _        <- cachedChatMetaData.getAndUpdate(_.updated(result._2, ChatMetaData(result._3, None)))
//          _        <- IO.println("ChatsRef:")
//          _        <- cachedChatMetaData.get.flatTap(IO.println)
//          topic    <- Topic[IO, Message]
//          _        <- topicsCache.getAndUpdate(map => map.updated(result._3, topic))
//          _        <- IO.println("TopicsRef:")
//          _        <- topicsCache.get.flatTap(IO.println)
//          response <- Ok {
//            s"""{
//              |  "username": "${result._1.username}",
//              |  "customerId": "${result._2}",
//              |  "chatId": "${result._3}"
//              |}""".stripMargin
//          }
//        } yield response

        case chatReq @ GET -> Root / "chat" => {


//          implicit val d: Decoder[CustomerJoin] = new Decoder[CustomerJoin] {
//            override def apply(c: HCursor): Result[CustomerJoin] =
//              for {
//                chatId <- c.get[String]("chatId")
//                customerId <- c.get[String]("customerId")
//                username <- c.get[String]("username")
//              } yield CustomerJoin(username, customerId, None, chatId)
//          }

//          implicit val RequestBodyDecoder: Decoder[RequestBody] = new Decoder[RequestBody] {
//            final def apply(c: HCursor): Decoder.Result[RequestBody] = {
//              for {
//                messageType <- c.get[String]("type")
//                args <- messageType match {
//                  case "CustomerJoin" => c.get[CustomerJoin]("args")
//                  case _ => Left(DecodingFailure("Invalid message type", c.history))
//                }
//              } yield RequestBody(messageType, args)
//            }
//          }

//          implicit val RequestBodyEntityDecoder: EntityDecoder[IO, RequestBody] = jsonOf[IO, RequestBody]

          val findTopicById: String => IO[Topic[IO, Message]] = chatId => topics.get.flatMap {
            _.get(chatId)
              .map(IO.pure)
              .getOrElse(IO.raiseError(new RuntimeException(s"Chat with $chatId does not exist")))
          }

          val receive: Pipe[IO, WebSocketFrame, Unit] = _.collect {
            case text: WebSocketFrame.Text => {
              println("line 252")
              val body = Json.parse(text.str.trim).as[RequestBody]
              println("243")
              body.args match {
                case CustomerJoin(username, customerId, supportId, chatId) =>
                  for {
                    _ <- IO.println("resp")
                    topic <- findTopicById(chatId)
                  } yield topic.publish.compose[Stream[IO, Main.Message]](_)
              }
            }
            case other => println("<other>")
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
//                       _ <- IO.println("movida rogorc iqna")
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

          val send: Stream[IO, WebSocketFrame.Text] = Stream.eval {
            for {
              body   <- chatReq.as[String].map(Json.parse(_).as[RequestBody])
              _ <- IO.println("298")
              chatId <- IO.pure(body.args match {
                case SupportJoin(_, _, chatId, _) => chatId
                case CustomerJoin(_, _, _, chatId) => chatId
              })
              topic <- findTopicById(chatId)
            } yield {
              topic.subscribe(10)
                .collect {
                  case in: In => in match {
                    case SupportJoin(username, customerId, chatId, supportId) =>
                      WebSocketFrame.Text("boom 1")
                    case CustomerJoin(username, customerId, supportId, chatId) =>
                      WebSocketFrame.Text("boom 2")
                  }
                }
            }
          }.flatMap(identity)

          wsb.build(send, receive)
        }
    }
  }.orNotFound
}
