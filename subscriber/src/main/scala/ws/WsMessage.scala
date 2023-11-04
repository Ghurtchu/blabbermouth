package ws

import play.api.libs.json.{Format, Json, Writes}

sealed trait WsMessage

object WsMessage {

  sealed trait In extends WsMessage
  sealed trait Out extends WsMessage

  object In {
    case object Load extends In
    case class JoinUser(userId: String, username: String, chatId: String) extends In

    object codecs {
      implicit val fju: Format[JoinUser] = Json.format[JoinUser]
    }
  }

  object Out {
    case class NewUser(userId: String, username: String, chatId: String) extends Out

    object codecs {
      implicit val wnu: Writes[NewUser] = Json.writes[NewUser]
      implicit val wo: Writes[Out] = { case n: NewUser =>
        wnu writes n
      }
    }
  }

}
