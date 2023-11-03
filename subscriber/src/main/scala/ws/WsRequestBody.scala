package ws

import ws.WsMessage.In
import ws.WsMessage.In.{JoinUser, Load}
import ws.WsMessage.In.codecs._
import play.api.libs.json.{JsError, JsResult, JsSuccess, JsValue, Reads}

case class WsRequestBody(`type`: String, args: Option[In])

object WsRequestBody {
  implicit val rr: Reads[WsRequestBody] = json =>
    for {
      typ <- (json \ "type")
        .validateOpt[String]
        .flatMap(_.map(JsSuccess(_)).getOrElse(JsError("empty `type`")))
      maybeArgs: Option[In] <- (json \ "args")
        .validateOpt[JsValue]
        .flatMap {
          case Some(args) =>
            typ match {
              case "JoinUser" => (implicitly[Reads[JoinUser]] reads args).map(Some(_))
              case "Load"     => JsError("`Load` should not have `args`")
              case _          => JsError("unrecognized `type`")
            }
          case None if typ == "Load" => JsSuccess(Some(Load))
          case _                     => JsError("empty `args`")
        }
    } yield WsRequestBody(typ, maybeArgs)
}
