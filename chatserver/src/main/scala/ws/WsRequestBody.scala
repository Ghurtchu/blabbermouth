package ws

import ws.Message.{ChatMessage, In}
import ws.Message.In.Join
import play.api.libs.json._

case class WsRequestBody(`type`: String, args: In)

object WsRequestBody {
  import In.codecs._
  import Message.codecs._

  implicit val rr: Reads[WsRequestBody] = json =>
    for {
      typ <- (json \ "type")
        .validateOpt[String]
        .flatMap(_.map(JsSuccess(_)).getOrElse(JsError("`type` is empty")))
      args <- (json \ "args")
        .validateOpt[JsValue]
        .flatMap {
          _.map { args =>
            typ match {
              case "Join"        => implicitly[Reads[Join]] reads args
              case "ChatMessage" => implicitly[Reads[ChatMessage]] reads args
              case _             => JsError("unrecognized `type`")
            }
          }.getOrElse(JsError("`args` is empty"))
        }
    } yield WsRequestBody(typ, args)
}
