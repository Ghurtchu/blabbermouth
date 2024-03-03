package domain

import play.api.libs.json._

sealed trait ChatParticipant {

  import ChatParticipant._

  def mirror: ChatParticipant = this match {
    case User    => Support
    case Support => User
  }
}
object ChatParticipant {

  case object User extends ChatParticipant {
    override def toString: String = "User"
  }
  case object Support extends ChatParticipant {
    override def toString: String = "Support"
  }

  def fromString: String => Option[ChatParticipant] =
    PartialFunction.condOpt(_) {
      case "User"    => User
      case "Support" => Support
    }

  private def toChatParticipant: String => JsResult[ChatParticipant] =
    ChatParticipant
      .fromString(_)
      .fold[JsResult[ChatParticipant]](JsError("unrecognized `ChatParticipant`"))(JsSuccess(_))

  implicit val wc: Writes[ChatParticipant] = (cp: ChatParticipant) => JsString(cp.toString)
  implicit val rc: Reads[ChatParticipant] = _.validate[String].flatMap(toChatParticipant)
}
