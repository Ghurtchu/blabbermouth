package domain

import play.api.libs.json.{Format, Json}

case class User(
  username: String,
  userId: String,
  chatId: String,
)

object User {
  implicit val fu: Format[User] = Json.format[User]
}
