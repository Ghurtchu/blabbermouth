package ws

import domain.{ChatParticipant, User}
import org.http4s.websocket.WebSocketFrame
import play.api.libs.json.{Format, Json, Reads, Writes}

import java.time.Instant

sealed trait Message

object Message {

  case object Pong extends Message

  sealed trait In extends Message
  sealed trait Out extends Message

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
