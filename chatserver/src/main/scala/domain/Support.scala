package domain

import play.api.libs.json.{Json, Writes}

case class Support(supportId: String, supportUserName: String, user: User)

object Support {
  implicit val ws: Writes[Support] = Json.writes[Support]
}
