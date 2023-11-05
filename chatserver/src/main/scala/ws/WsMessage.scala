package ws

import domain.{ChatParticipant, User}
import org.http4s.websocket.WebSocketFrame
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
    implicit val cmf: Format[ChatMessage] = Json.format[ChatMessage]
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
      ): ChatHistory =
        new ChatHistory(User(username, userId, chatId), Vector.empty)
    }

    case class UserJoined(user: domain.User) extends Out
    case class SupportJoined(support: domain.Support) extends Out
    case class ChatExpired(chatId: String) extends Out
    case class UserLeft(chatId: String) extends Out
    case class SupportLeft(chatId: String) extends Out

    object codecs {
      import WsMessage.codecs._

      implicit val wuj: Writes[UserJoined] = implicitly[Writes[domain.User]].contramap(_.user)
      implicit val wsj: Writes[SupportJoined] = implicitly[Writes[domain.Support]].contramap(_.support)
      implicit val wce: Writes[ChatExpired] = Json.writes[ChatExpired]
      implicit val wch: Writes[ChatHistory] = Json.writes[ChatHistory]
      implicit val wr: Writes[Registered] = Json.writes[Registered]
      implicit val rj: Reads[Join] = Json.reads[Join]
      implicit val wul: Writes[UserLeft] = Json.writes[UserLeft]
      implicit val wsl: Writes[SupportLeft] = Json.writes[SupportLeft]
      implicit val wo: Writes[Out] = {
        case out: UserJoined    => wuj writes out
        case out: SupportJoined => wsj writes out
        case out: ChatMessage   => cmf writes out
        case out: Registered    => wr writes out
        case out: ChatHistory   => wch writes out
        case out: ChatExpired   => wce writes out
        case out: UserLeft      => wul writes out
        case out: SupportLeft   => wsl writes out
      }
    }
  }
}
