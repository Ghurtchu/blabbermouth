package messages

import messages.Message.In
import messages.Message.In.{JoinUser, Load}
import messages.Message.In.codecs._
import play.api.libs.json.{JsError, JsResult, JsSuccess, JsValue, Reads}

case class Request(`type`: String, args: Option[In])

object Request {
  implicit val rr: Reads[Request] = json =>
    for {
      typ <- (json \ "type")
        .validateOpt[String]
        .flatMap(_.fold[JsResult[String]](JsError("empty `type`"))(JsSuccess(_)))
      maybeArgs: Option[In] <- (json \ "args")
        .validateOpt[JsValue]
        .flatMap {
          case Some(args) =>
            typ match {
              case "JoinUser" => (implicitly[Reads[JoinUser]] reads args).map(Some.apply)
              case _          => JsError("unrecognized `type`")
            }
          case None if typ == "Load" => JsSuccess(Some(Load))
          case _                     => JsError("empty `args`")
        }
    } yield Request(typ, maybeArgs)
}
