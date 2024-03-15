package ws

import ws.Message.{ChatMessage, In}
import ws.Message.In.{Join, Pong}
import play.api.libs.json._

// WebSocket message sent from Client to Server
case class ClientWsMsg(`type`: String, args: In)

object ClientWsMsg {
  import In.codecs._
  import Message.codecs._

  implicit val rr: Reads[ClientWsMsg] = json =>
    for {
      typ <- (json \ "type")
        .validateOpt[String]
        .flatMap(_.map(JsSuccess(_)).getOrElse(JsError("`type` is empty")))
      args <- (json \ "args")
        .validateOpt[JsValue]
        .flatMap {
          case Some(args) =>
            typ match {
              case "Join"        => implicitly[Reads[Join]] reads args
              case "ChatMessage" => implicitly[Reads[ChatMessage]] reads args
              case "Pong"        => implicitly[Reads[Pong]] reads args
              case _             => JsError("unrecognized `type`")
            }
          case None => JsError("`args` is empty")
        }
    } yield ClientWsMsg(typ, args)
}
