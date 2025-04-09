package ws

import ws.Message.Out
import play.api.libs.json._

/** Outgoing WebSocket message from Backend to Support UI
  */
case class ServerWsMsg(args: Out) extends AnyVal {
  def `type`: String = args.getClass.getSimpleName
}

object ServerWsMsg {
  import Message.Out.codecs.WritesOut

  implicit val WritesServerWsMsg: Writes[ServerWsMsg] =
    (msg: ServerWsMsg) =>
      JsObject(
        Map(
          "type" -> JsString(msg.`type`),
          "args" -> WritesOut.writes(msg.args),
        ),
      )
}
