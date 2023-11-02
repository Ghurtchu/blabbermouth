package domain

import play.api.libs.json._

sealed trait ChatParticipant {

  import ChatParticipant._
  override def toString: String = this.getClass.getSimpleName.init // drops dollar sign

  def mirror: ChatParticipant = this match {
    case User    => Support
    case Support => User
  }
}
object ChatParticipant {

  case object User extends ChatParticipant
  case object Support extends ChatParticipant

  def fromString: String => Option[ChatParticipant] = PartialFunction.condOpt(_) {
    case "User"    => User
    case "Support" => Support
  }

  private def toChatParticipant: String => JsResult[ChatParticipant] =
    ChatParticipant
      .fromString(_)
      .map(JsSuccess(_))
      .getOrElse(JsError("unrecognized `ChatParticipant` value"))

  implicit val wc: Writes[ChatParticipant] = (cp: ChatParticipant) => JsString(cp.toString)
  implicit val rc: Reads[ChatParticipant] = _.validate[String].flatMap(toChatParticipant)
}
