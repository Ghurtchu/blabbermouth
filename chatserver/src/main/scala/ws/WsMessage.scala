package ws

import domain.{ChatParticipant, User}
import play.api.libs.json.{Format, Json, Reads, Writes}

import java.time.Instant

sealed trait WsMessage

object WsMessage {

  sealed trait In extends WsMessage
  sealed trait Out extends WsMessage

  case class ChatMessage(
    content: String,
    from: ChatParticipant,
    userId: String,
    supportId: String,
    timestamp: Option[Instant] = None,
  ) extends In
      with Out

  object codecs {
    implicit val cf: Format[ChatMessage] = Json.format[ChatMessage]
  }

  object In {

    case class Join(
      from: ChatParticipant,
      userId: String,
      username: String,
      supportId: Option[String],
      supportUserName: Option[String],
    ) extends In

    object codecs {
      implicit val rj: Reads[Join] = Json.reads[Join]
    }
  }

  object Out {
    import In._

    case class Registered(
      userId: String,
      username: String,
      chatId: String,
    ) extends Out

    case class ChatHistory(user: User, messages: Vector[ChatMessage]) extends Out {
      def +(msg: ChatMessage): ChatHistory = copy(messages = messages :+ msg)
    }

    object ChatHistory {
      def init(username: String, userId: String, chatId: String): ChatHistory =
        new ChatHistory(User(username, userId, chatId), Vector.empty)
    }

    case class Joined(
      participant: ChatParticipant,
      userId: String,
      chatId: String,
      supportId: Option[String],
      supportUserName: Option[String],
    ) extends Out

    case class ChatExpired(chatId: String) extends Out

    object codecs {
      import WsMessage.codecs._

      implicit val wc: Writes[ChatExpired] = Json.writes[ChatExpired]
      implicit val wj: Writes[Joined] = Json.writes[Joined]
      implicit val wch: Writes[ChatHistory] = Json.writes[ChatHistory]
      implicit val wr: Writes[Registered] = Json.writes[Registered]
      implicit val rj: Reads[Join] = Json.reads[Join]
      implicit val wo: Writes[Out] = {
        case out: ChatMessage => implicitly[Writes[ChatMessage]] writes out
        case out: Registered  => implicitly[Writes[Registered]] writes out
        case out: ChatHistory => implicitly[Writes[ChatHistory]] writes out
        case out: Joined      => implicitly[Writes[Joined]] writes out
        case out: ChatExpired => implicitly[Writes[ChatExpired]] writes out
      }
    }
  }
}
