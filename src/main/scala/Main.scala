import cats.effect.std.Queue
import cats.effect.{ExitCode, IO, IOApp, Ref}
import com.comcast.ip4s.IpLiteralSyntax
import fs2.Stream
import fs2.concurrent.Topic
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpApp, HttpRoutes}
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
   * - join the chat support
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

  final case class MessageFromCustomer(customerId: String, supportId: String, content: String) extends ChatMessage with FromCustomer with ToSupport
  final case class MessageFromSupport(supportId: String, customerId: String, content: String)  extends ChatMessage with FromSupport with ToCustomer

  final case class Conversation(messages: Vector[ChatMessage]) extends ToCustomer with ToSupport {
    def add(msg: ChatMessage): Conversation =
      copy(messages :+ msg)
  }

  object Conversation {
    def empty: Conversation = Conversation(Vector.empty)
  }

  final case class Connect(username: String) extends FromCustomer
  final case class Disconnect(id: String)    extends FromCustomer

  final case class SupportJoin(username: String, customerId: String, chatId: String, supportId: Option[String]) extends In
  final case class CustomerJoin(username: String, customerId: String, supportId: Option[String]) extends In

  final case class Connected(customerId: String, username: String) extends ToCustomer
  final case class SupportJoined(supportId: String, supportUsername: String, customerId: String, chatId: String) extends ToSupport
  final case class CustomerJoined(customerId: String, chatId: String) extends ToSupport

  implicit val ChatMessageFormat: Format[ChatMessage] = new Format[ChatMessage] {
    override def reads(json: JsValue): JsResult[ChatMessage] =
      MessageFromCustomerFormat.reads(json) orElse MessageFromSupportFormat.reads(json)

    override def writes(o: ChatMessage): JsValue = o match {
      case mfc @ MessageFromCustomer(customerId, supportId, content) => MessageFromCustomerFormat.writes(mfc)
      case mfs @ MessageFromSupport(supportId, customerId, content) => MessageFromSupportFormat.writes(mfs)
    }
  }

  implicit val MessageFromCustomerFormat: Format[MessageFromCustomer] = Json.format[MessageFromCustomer]
  implicit val MessageFromSupportFormat: Format[MessageFromSupport] = Json.format[MessageFromSupport]

  implicit val ConversationFormat: Format[Conversation] = Json.format[Conversation]

  implicit val ConnectFormat: Format[Connect] = Json.format[Connect]
  implicit val SupportJoinFormat: Format[SupportJoin] = Json.format[SupportJoin]
  implicit val CustomerJoinFormat: Format[CustomerJoin] = Json.format[CustomerJoin]
  implicit val DisconnectFormat: Format[Disconnect] = Json.format[Disconnect]

  implicit val ConnectedFormat: Format[Connected] = Json.format[Connected]
  implicit val SupportJoinedFormat: Format[SupportJoined] = Json.format[SupportJoined]
  implicit val CustomerJoinedFormat: Format[CustomerJoined] = Json.format[CustomerJoined]

  final case class RequestBody(`type`: String, args: In)

  implicit val RequestBodyFormat: Reads[RequestBody] = new Reads[RequestBody] {
    override def reads(json: JsValue): JsResult[RequestBody] =
      (json \ "type").validate[String] flatMap {
        case m @ "MessageFromCustomer" => MessageFromCustomerFormat.reads(json("args"))
          .map(RequestBody(m, _))
        case s @ "MessageFromSupport" => MessageFromSupportFormat.reads(json("args"))
          .map(RequestBody(s, _))
        case c @ "Connect" => ConnectFormat.reads(json("args"))
          .map(RequestBody(c, _))
        case d @ "Disconnect" => DisconnectFormat.reads(json("args"))
          .map(RequestBody(d, _))
        case j @ "SupportJoin" => SupportJoinFormat.reads(json("args"))
          .map(RequestBody(j, _))
        case c @ "CustomerJoin" => CustomerJoinFormat.reads(json("args"))
          .map(RequestBody(c, _))
      }
  }

  val run = for {
    // topics
    // customer id -> topic
    topicsRef <- IO.ref(Map.empty[CustomerId, Topic[IO, Message]])

    // chats
    // chat id -> customer id
    cachedChatMetaData <- IO.ref(Map.empty[CustomerId, ChatMetaData])
    cachedConversations <- IO.ref(Map.empty[CustomerId, Conversation])
    _ <- EmberServerBuilder
    .default[IO]
    .withHost(host"0.0.0.0")
    .withPort(port"8080")
    .withHttpWebSocketApp(httpApp(_, cachedChatMetaData, topicsRef, cachedConversations))
    .build
      .useForever
  } yield ExitCode.Success

  private def httpApp(
                       wsb: WebSocketBuilder2[IO],
                       cachedChatMetaData: Ref[IO, CachedChatMetaData],
                       topicsCache: Ref[IO, TopicsCache],
                       cachedConversations: Ref[IO, Map[CustomerId, Conversation]]
  ): HttpApp[IO] = {
    val dsl = new Http4sDsl[IO] {}
    import dsl._
    HttpRoutes.of[IO] {
      case req @ GET -> Root / "join" =>
        for {
          connect <- req.as[String].map(Json.parse(_).as[RequestBody].args.asInstanceOf[Connect]).flatTap(IO.println)
          result  <- IO.delay {
            val chatId = java.util.UUID.randomUUID().toString.replaceAll("-", "")
            val userId = java.util.UUID.randomUUID().toString.replaceAll("-", "")

            (connect, userId, chatId)
          }
          _        <- cachedChatMetaData.getAndUpdate(_.updated(result._2, ChatMetaData(result._3, None)))
          _        <- IO.println("ChatsRef:")
          _        <- cachedChatMetaData.get.flatTap(IO.println)
          topic    <- Topic[IO, Message]
          _        <- topicsCache.getAndUpdate(map => map.updated(result._3, topic))
          _        <- IO.println("TopicsRef:")
          _        <- topicsCache.get.flatTap(IO.println)
          response <- Ok {
            s"""{
              |  "username": "${result._1.username}",
              |  "customerId": "${result._2}"
              |  "chatId": "${result._3}"
              |}""".stripMargin
          }
        } yield response

      case GET -> Root / "chat" / chatId => {

        val lazyTopic = topicsCache.get.flatMap {
          _.get(chatId)
            .map(IO.pure)
            .getOrElse(IO.raiseError(new RuntimeException(s"Chat with $chatId does not exist")))
        }

        val lazySend: IO[Stream[IO, WebSocketFrame.Text]] = lazyTopic.map {
          _.subscribe(maxQueued = 10)
            .evalMapFilter {
              case out: Out => out match {

                case j@SupportJoined(supportId, supportUsername, toCustomerId, chatId) =>
                  for {
                    _ <- cachedChatMetaData.getAndUpdate(_.updated(toCustomerId, ChatMetaData(chatId, Some(supportId))))
                      .flatTap(IO.println)
                    resp <- IO.pure {
                      Some {
                        WebSocketFrame.Text {
                          s"""
                            |{
                            |  "supportId": "${supportId}"
                            |}
                            |""".stripMargin
                        }
                      }
                    }
                  } yield resp

                case mfc@MessageFromCustomer(customerId, supportId, content) =>
                  for {
                    isValid  <- cachedChatMetaData.get.map {
                      _.get(customerId)
                        .map(_.supportId)
                        .exists(_.contains(supportId))
                    }
                    _ <- cachedConversations.getAndUpdate { map =>
                      map.updatedWith(customerId) {
                        case Some(conversation) => Some(conversation.add(mfc))
                        case None               => Some(Conversation(Vector(mfc)))
                      }
                    }
                    response <- IO.pure(Option.when(isValid)(WebSocketFrame.Text(content)))
                  } yield response

                case mfs@MessageFromSupport(supportId, customerId, content) =>
                  for {
                    isValid <- cachedChatMetaData.get.map {
                      _.get(customerId)
                        .map(_.supportId)
                        .exists(_.contains(supportId))
                    }
                    _ <- cachedConversations.getAndUpdate { map =>
                      map.updatedWith(customerId) {
                        case Some(conversation) => Some(conversation.add(mfs))
                        case None => Some(Conversation(Vector(mfs)))
                      }
                    }
                    response <- IO.pure(Option.when(isValid)(WebSocketFrame.Text(content)))
                  } yield response
                case conv @ Conversation(msgs) => IO.pure(Option(WebSocketFrame.Text(ConversationFormat.writes(conv).toString())))

                case _: CustomerJoined => IO.pure(Some(WebSocketFrame.Text("Waiting for support...")))

              }
            }
        }

        val lazyReceive = lazyTopic.map { topic =>
          topic.publish
            .compose[Stream[IO, WebSocketFrame]](_.evalMapFilter {
              case text: WebSocketFrame.Text => {
                val request = Json.parse(text.str.trim).as[RequestBody]
                request.args match {
                  case mfc: MessageFromCustomer =>
                    for {
                      isValid <- cachedChatMetaData.get.map {
                        _.get(mfc.customerId)
                          .map(_.supportId)
                          .exists(_.contains(mfc.supportId))
                      }
                      msg <- IO(Option.when(isValid)(mfc))
                    } yield msg
                  case mfs: MessageFromSupport =>
                    for {
                      isValid <- cachedChatMetaData.get.map {
                        _.get(mfs.customerId)
                          .map(_.supportId)
                          .exists(_.contains(mfs.supportId))
                      }
                      msg <- IO(Option.when(isValid)(mfs))
                    } yield msg
                  case SupportJoin(supportUsername, customerId, chatId, None) =>
                    for {
//                      exists <- cachedChatMetaData.get.map {
//                        _.get(customerId)
//                          .map(_.chatId)
//                          .exists(_.contains(chatId))
//                      }
                      // _ <- IO.println(s"exists? $exists")
                      msg <- IO.delay {
                        val supportId = java.util.UUID.randomUUID().toString.replaceAll("-", "")
                        println("here :))")

                        Some(SupportJoined(supportId, supportUsername, customerId, chatId))
                      }
                    } yield msg

                  case SupportJoin(supportUsername, customerId, chatId, Some(supportId)) =>
                    for {
                      exists <- cachedChatMetaData.get.map {
                        _.get(customerId)
                          .map(_.chatId)
                          .exists(_.contains(chatId))
                      }
                      _ <- IO.println(s"exists? $exists")
                      convos <- cachedConversations.get.map(_.getOrElse(customerId, Conversation.empty))
                      msg <- if (exists) IO(Some(convos)) else IO.delay {

                        Some(SupportJoined(supportId, supportUsername, customerId, chatId))
                      }
                    } yield msg

                  case CustomerJoin(username, customerId, Some(supportId)) =>
                    for {
                      exists <- cachedChatMetaData.get.map {
                        _.get(customerId)
                          .map(_.supportId)
                          .exists(_.contains(supportId))
                      }
                      _ <- IO.println {
                        if (exists) println("support and customer already exists")
                        else println("support and customer already exists not ")
                      }
                      convos <- cachedConversations.get.map(_.getOrElse(customerId, Conversation.empty))
                      msg <- if (exists) IO(Some(convos)) else IO.pure(Some(CustomerJoined(customerId, chatId)))
                    } yield msg

                  case CustomerJoin(username, customerId, None) =>
                    IO.pure(Some(CustomerJoined(customerId, chatId)))
                }

              }
            })
        }

        val send: Stream[IO, WebSocketFrame.Text] = Stream.eval(lazySend).flatMap(identity)

        val receive: Stream[IO, WebSocketFrame] => Stream[IO, Nothing] = (input: Stream[IO, WebSocketFrame]) =>
          Stream.eval(lazyReceive).flatMap(f => f(input))

        wsb.build(send, receive)
      }
    }
  }.orNotFound
}
