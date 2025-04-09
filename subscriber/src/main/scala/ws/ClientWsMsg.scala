package ws

import ws.Message.In
import ws.Message.In.{JoinUser, LoadPendingUsers}
import ws.Message.In.codecs._
import play.api.libs.json.{JsError, JsSuccess, JsValue, Reads}

/** Incoming WebSocket message from Support UI to Backend
  */
case class ClientWsMsg(`type`: String, args: Option[In])

object ClientWsMsg {
  implicit val ReadsClientWsMsg: Reads[ClientWsMsg] = json =>
    for {
      typ <- (json \ "type")
        .validateOpt[String]
        .flatMap(_.map(JsSuccess(_)).getOrElse(JsError("`type` is empty")))
      argsOpt <- (json \ "args")
        .validateOpt[JsValue]
        .flatMap {
          case Some(args) =>
            typ match {
              case "JoinUser" => (implicitly[Reads[JoinUser]] reads args).map(Some(_))
              case "Load"     => JsError("`Load` should not have `args`")
              case _          => JsError("unrecognized `type`")
            }
          case None if typ == "Load" => JsSuccess(Some(LoadPendingUsers))
          case None if typ.nonEmpty  => JsError("unrecognized `type`")
          case _                     => JsError("empty `args`")
        }
    } yield ClientWsMsg(typ, argsOpt)
}
