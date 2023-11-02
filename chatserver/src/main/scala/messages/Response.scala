package messages

import messages.WebSocketMessage.Out
import play.api.libs.json._

case class Response(args: Out) extends AnyVal {
  def `type`: String = args.getClass.getSimpleName
}

object Response {
  import WebSocketMessage.Out.codecs.wo

  implicit val wr: Writes[Response] = (response: Response) =>
    JsObject(
      Map(
        "type" -> JsString(response.`type`),
        "args" -> wo.writes(response.args),
      ),
    )
}
