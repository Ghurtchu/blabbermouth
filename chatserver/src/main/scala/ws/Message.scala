package ws

import domain.{ChatParticipant, User}
import org.http4s.websocket.WebSocketFrame
import play.api.libs.json
import play.api.libs.json.{Format, JsError, JsSuccess, Json, Reads, Writes}
import play.api.libs.json.{JsError, JsSuccess, JsValue, Reads}

import java.time.Instant

/** Hierarchy of messages which can be:
  *   - sent from Client to Server (In)
  *   - sent from Server to Client (Out)
  */
sealed trait Message

object Message {

  // Parent type of messages which can be sent from Client to Server
  sealed trait In extends Message
  // Parent type of messages which can be sent from Server to Client
  sealed trait Out extends Message

  // ChatMessage can be sent both ways (from & to Client & Server)
  case class ChatMessage(
    content: String,
    from: ChatParticipant,
    userId: String,
    supportId: String,
    timestamp: Option[Instant] = None,
  ) extends In
      with Out

  object codecs {
    implicit val cmf: Format[ChatMessage] = Json.format[ChatMessage]
  }

  // Groups all In messages
  object In {

    // Pong responses from User and Support for managing WS connection
    case class Pong(from: ChatParticipant) extends In

    /** Used in case Client sends:
      *   - unrecognizable / malformed Json
      *   - any other thing that can't be parsed from contextually available Reads[ClientWsMsg]
      */
    case object UnknownMessage extends In

    /** Received from Client to Server when:
      *   - User tries to Join the system
      *   - Support tries to Join the User
      */
    case class Join(
      from: ChatParticipant,
      userId: String,
      username: String,
      supportId: Option[String],
      supportUserName: Option[String],
    ) extends In

    object codecs {
      implicit val rp: Reads[Pong] = json =>
        for {
          from <- (json \ "from").validateOpt[String]
          result <- from match {
            case Some("User")    => JsSuccess(Pong(ChatParticipant.User))
            case Some("Support") => JsSuccess(Pong(ChatParticipant.Support))
            case Some(other)     => JsError(s"unrecognized `from`: $other")
            case None            => JsError("empty `from`")
          }
        } yield result
      implicit val rj: Reads[Join] = Json.reads[Join]
    }
  }

  // Groups all Out messages
  object Out {
    import In._

    // Sent from Server to Client when they get registered in the system
    case class Registered(
      userId: String,
      username: String,
      chatId: String,
    ) extends Out

    // Sent from Server to Client to load the chat history
    case class ChatHistory(
      user: User,
      messages: Vector[ChatMessage],
    ) extends Out {
      def +(msg: ChatMessage): ChatHistory =
        copy(messages = messages :+ msg)
    }

    object ChatHistory {
      def init(
        username: String,
        userId: String,
        chatId: String,
      ): ChatHistory = ChatHistory(
        user = User(username, userId, chatId),
        messages = Vector.empty,
      )
    }

    // Sent to Client when User joins the chat system for the first time
    case class UserJoined(user: domain.User) extends Out
    // Sent to Client when Support joins the chat system for the first time
    case class SupportJoined(support: domain.Support) extends Out
    // Sent to Client when chat gets expired
    case class ChatExpired(chatId: String) extends Out
    // Sent to Client when User leaves the chat system
    case class UserLeft(chatId: String) extends Out
    // Sent to Client when Support leaves the chat system
    case class SupportLeft(chatId: String) extends Out

    object codecs {
      import Message.codecs._

      implicit val wuj: Writes[UserJoined] = implicitly[Writes[domain.User]].contramap(_.user)
      implicit val wsj: Writes[SupportJoined] = implicitly[Writes[domain.Support]].contramap(_.support)
      implicit val wce: Writes[ChatExpired] = Json.writes[ChatExpired]
      implicit val wch: Writes[ChatHistory] = Json.writes[ChatHistory]
      implicit val wr: Writes[Registered] = Json.writes[Registered]
      implicit val rj: Reads[Join] = Json.reads[Join]
      implicit val wul: Writes[UserLeft] = Json.writes[UserLeft]
      implicit val wsl: Writes[SupportLeft] = Json.writes[SupportLeft]
      implicit val wo: Writes[Out] = {
        case o: UserJoined    => wuj writes o
        case o: SupportJoined => wsj writes o
        case o: ChatMessage   => cmf writes o
        case o: Registered    => wr writes o
        case o: ChatHistory   => wch writes o
        case o: ChatExpired   => wce writes o
        case o: UserLeft      => wul writes o
        case o: SupportLeft   => wsl writes o
      }
    }
  }
}
