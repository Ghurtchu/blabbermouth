package redis

import domain.User
import play.api.libs.json._

case class PubSubMessage[A](`type`: String, args: A)

object PubSubMessage {
  implicit def writesPubSubMessage[A: Writes]: Writes[PubSubMessage[A]] =
    (message: PubSubMessage[A]) =>
      JsObject(
        Map(
          "type" -> JsString(message.`type`),
          "args" -> implicitly[Writes[A]].writes(message.args),
        ),
      )

  case class UserLeft(user: User) extends AnyVal
  implicit val wu: Writes[UserLeft] = implicitly[Writes[User]].contramap(_.user)
}
