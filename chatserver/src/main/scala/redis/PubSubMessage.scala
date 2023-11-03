package redis

import play.api.libs.json._

import java.time.Instant

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
}
