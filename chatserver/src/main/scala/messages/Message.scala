package messages

import domain.{From, User}
import play.api.libs.json.{Format, Json, Reads, Writes}

import java.time.Instant

sealed trait Message

object Message {

  sealed trait In extends Message
  sealed trait Out extends Message

  case class ChatMessage(
    content: String,
    from: From,
    userId: String,
    supportId: String,
    timestamp: Option[Instant] = None,
  ) extends In
      with Out

  object codecs {
    implicit val chatMsgFmt: Format[ChatMessage] = Json.format[ChatMessage]
  }
  object In {
    case class Join(
      from: From,
      userId: String,
      username: String,
      supportId: Option[String],
      supportUserName: Option[String],
    ) extends In

    object codecs {
      implicit val readsJoin: Reads[Join] = Json.reads[Join]
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
      participant: From,
      userId: String,
      chatId: String,
      supportId: Option[String],
      supportUserName: Option[String],
    ) extends Out

    case class ChatExpired(chatId: String) extends Out
    object codecs {
      import Message.codecs._
      implicit val writesChatExpired: Writes[ChatExpired] = Json.writes[ChatExpired]
      implicit val writesJoined: Writes[Joined] = Json.writes[Joined]
      implicit val writesChatHistory: Writes[ChatHistory] = Json.writes[ChatHistory]
      implicit val writesRegistered: Writes[Registered] = Json.writes[Registered]
      implicit val readsJoin: Reads[Join] = Json.reads[Join]
      implicit val outFmt: Writes[Out] = {
        case m: ChatMessage => chatMsgFmt.writes(m)
        case c: Registered  => writesRegistered.writes(c)
        case c: ChatHistory => writesChatHistory.writes(c)
        case j: Joined      => writesJoined.writes(j)
        case c: ChatExpired => writesChatExpired.writes(c)
      }
    }
  }
}
