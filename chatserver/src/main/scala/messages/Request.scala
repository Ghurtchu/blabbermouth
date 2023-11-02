package messages

import messages.WebSocketMessage.{ChatMessage, In}
import messages.WebSocketMessage.In.Join
import play.api.libs.json._

case class Request(`type`: String, args: In)

object Request {
  import In.codecs._
  import WebSocketMessage.codecs._

  implicit val rr: Reads[Request] = json =>
    for {
      typ <- (json \ "type")
        .validateOpt[String]
        .flatMap(_.fold[JsResult[String]](JsError("`type` is empty"))(JsSuccess(_)))
      in <- (json \ "args")
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
    } yield Request(typ, in)
}
