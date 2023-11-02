package messages

import play.api.libs.json._

case class PubSubMessage[A](`type`: String, args: A)

object PubSubMessage {
  implicit def writesPubSubMessage[A: Writes]: Writes[PubSubMessage[A]] = (pubSubMessage: PubSubMessage[A]) =>
    JsObject(
      Map(
        "type" -> JsString(pubSubMessage.`type`),
        "args" -> implicitly[Writes[A]].writes(pubSubMessage.args),
      ),
    )
}
