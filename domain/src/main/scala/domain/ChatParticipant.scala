package domain

import play.api.libs.json._

import scala.PartialFunction.condOpt

sealed trait ChatParticipant {

  import ChatParticipant._

  def code: String

  def mirror: ChatParticipant = this match {
    case User    => Support
    case Support => User
  }
}
object ChatParticipant {

  case object User extends ChatParticipant {
    def code: String = "User"
  }

  case object Support extends ChatParticipant {
    def code: String = "Support"
  }

  def fromString: String => Option[ChatParticipant] =
    condOpt(_) {
      case "User"    => User
      case "Support" => Support
    }

  private def toChatParticipant: String => JsResult[ChatParticipant] =
    ChatParticipant
      .fromString(_)
      .map(JsSuccess(_))
      .getOrElse(JsError("unrecognized `ChatParticipant`"))

  implicit val wc: Writes[ChatParticipant] =
    (cp: ChatParticipant) => JsString(cp.toString)
  implicit val rc: Reads[ChatParticipant] =
    _.validate[String].flatMap(toChatParticipant)
}
