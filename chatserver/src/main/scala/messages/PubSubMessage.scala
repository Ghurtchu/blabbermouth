package messages

import play.api.libs.json._

case class PubSubMessage[A](`type`: String, args: A)

object PubSubMessage {
  implicit def writesPubSubMessage[A: Writes]: Writes[PubSubMessage[A]] = (msg: PubSubMessage[A]) =>
    JsObject(
      Map(
        "type" -> JsString(msg.`type`),
        "args" -> implicitly[Writes[A]].writes(msg.args),
      ),
    )
}
