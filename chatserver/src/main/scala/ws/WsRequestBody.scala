package ws

import ws.WsMessage.{ChatMessage, In}
import ws.WsMessage.In.Join
import play.api.libs.json._

case class WsRequestBody(`type`: String, args: In)

object WsRequestBody {
  import In.codecs._
  import WsMessage.codecs._

  implicit val rr: Reads[WsRequestBody] = json =>
    for {
      typ <- (json \ "type")
        .validateOpt[String]
        .flatMap(_.fold[JsResult[String]](JsError("`type` is empty"))(JsSuccess(_)))
      args <- (json \ "args")
        .validateOpt[JsValue]
        .flatMap {
          _.fold[JsResult[In]](JsError("`args` is empty")) { args =>
            typ match {
              case "Join"        => implicitly[Reads[Join]] reads args
              case "ChatMessage" => implicitly[Reads[ChatMessage]] reads args
              case _             => JsError("unrecognized `type`")
            }
          }
        }
    } yield WsRequestBody(typ, args)
}
