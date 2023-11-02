package messages

import messages.Message.In
import play.api.libs.json.{JsError, Reads}

case class Request(`type`: String, args: In)

object Request {

  import In.codecs._
  import Message.codecs._

  implicit val readsRequest: Reads[Request] = json =>
    for {
      typ <- (json \ "type").validate[String]
      args = json("args")
      in <- typ match {
        case "Join"        => readsJoin.reads(args)
        case "ChatMessage" => chatMsgFmt.reads(args)
        case _             => JsError("unrecognized type")
      }
    } yield Request(typ, in)
}
