package ws

import ws.WsMessage.Out
import play.api.libs.json._

case class WsResponseBody(args: Out) extends AnyVal {
  def `type`: String = args.getClass.getSimpleName
}

object WsResponseBody {
  import WsMessage.Out.codecs.wo

  implicit val ww: Writes[WsResponseBody] =
    (body: WsResponseBody) =>
      JsObject(
        Map(
          "type" -> JsString(body.`type`),
          "args" -> wo.writes(body.args),
        ),
      )
}
