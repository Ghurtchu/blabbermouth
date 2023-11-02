package domain

import play.api.libs.json._

sealed trait ChatParticipant {

  import ChatParticipant._

  /** if the subtype is User returns "User" otherwise returns "Support"
    *
    * if the subtype was User then this.getClass.getSimpleName would return "User$"
    *
    * so, calling .init in the end drops the dollar sign from "User$" and returns "User"
    */
  override def toString: String = this.getClass.getSimpleName.init

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
      .fold[JsResult[ChatParticipant]](JsError("unrecognized `ChatParticipant`"))(JsSuccess(_))

  implicit val wc: Writes[ChatParticipant] = (cp: ChatParticipant) => JsString(cp.toString)
  implicit val rc: Reads[ChatParticipant] = _.validate[String].flatMap(toChatParticipant)
}
