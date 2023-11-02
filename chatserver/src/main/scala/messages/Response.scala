package messages

import messages.Message.Out
import play.api.libs.json._

case class Response(args: Out)

object Response {

  import Message.Out.codecs.outFmt

  implicit val writesResponse: Writes[Response] = response => {
    val `type` = response.args.getClass.getSimpleName
    JsObject(Map("type" -> JsString(`type`), "args" -> outFmt.writes(response.args)))
  }
}
