package domain

import play.api.libs.json._

sealed trait From {

  import From._
  override def toString: String = this.getClass.getSimpleName.init // drops dollar sign

  def mirror: From = this match {
    case User    => Support
    case Support => User
  }
}
object From {

  case object User extends From
  case object Support extends From

  def fromString: String => Option[From] = PartialFunction.condOpt(_) {
    case "User"    => User
    case "Support" => Support
  }

  private def toFrom: String => JsResult[From] =
    From
      .fromString(_)
      .map(JsSuccess(_))
      .getOrElse(JsError("unrecognized `From` value"))

  implicit val writesParticipant: Writes[From] = p => JsString(p.toString)
  implicit val readsParticipant: Reads[From] = _.validate[String].flatMap(toFrom)
}
