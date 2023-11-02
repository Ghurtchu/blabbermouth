package ws

import ws.WsMessage.Out
import ws.WsMessage.Out.codecs._
import play.api.libs.json._

case class WsResponseBody(args: Out) extends AnyVal {
  def `type` = args.getClass.getSimpleName
}

object WsResponseBody {
  implicit val wr: Writes[WsResponseBody] =
    (webSocketResponse: WsResponseBody) => {
      val `type` = webSocketResponse.args.getClass.getSimpleName
      JsObject(
        Map(
          "type" -> JsString(`type`),
          "args" -> (implicitly[Writes[Out]] writes webSocketResponse.args),
        ),
      )
    }
}
