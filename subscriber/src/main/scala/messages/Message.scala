package messages

import play.api.libs.json.{Format, Json, Writes}

sealed trait Message

object Message {

  sealed trait In extends Message
  sealed trait Out extends Message

  object In {
    case object Load extends In
    case class JoinUser(userId: String, username: String, chatId: String) extends In

    object codecs {
      implicit val fj: Format[JoinUser] = Json.format[JoinUser]
    }
  }

  object Out {
    case class NewUser(userId: String, username: String, chatId: String) extends Out

    object codecs {
      implicit val wn: Writes[NewUser] = Json.writes[NewUser]
      implicit val writesOut: Writes[Out] = { case n: NewUser =>
        wn.writes(n)
      }
    }
  }

}
