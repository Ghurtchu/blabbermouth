package ws

import play.api.libs.json.{Format, Json, Writes}

sealed trait Message

object Message {

  sealed trait In extends Message
  sealed trait Out extends Message

  object In {
    case object Load extends In
    case class JoinUser(userId: String, username: String, chatId: String) extends In

    object codecs {
      implicit val fju: Format[JoinUser] = Json.format[JoinUser]
    }
  }

  object Out {
    case class NewUser(userId: String, username: String, chatId: String) extends Out
    case class RemoveUser(userId: String) extends Out

    object codecs {
      implicit val wnu: Writes[NewUser] = Json.writes[NewUser]
      implicit val wru: Writes[RemoveUser] = Json.writes[RemoveUser]
      implicit val wo: Writes[Out] = {
        case n: NewUser     => wnu writes n
        case ru: RemoveUser => wru writes ru
      }
    }
  }
}
