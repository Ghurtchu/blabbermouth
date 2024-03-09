package domain

import play.api.libs.json.{Json, Writes}

case class User(
  username: String,
  userId: String,
  chatId: String,
)

object User {
  implicit val wu: Writes[User] = Json.writes[User]
}
