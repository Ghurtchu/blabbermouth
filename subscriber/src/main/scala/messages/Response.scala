package messages

import messages.Message.Out
import messages.Message.Out.codecs._
import play.api.libs.json._

case class Response(args: Out) extends AnyVal {
  def `type` = args.getClass.getSimpleName
}

object Response {
  implicit val wr: Writes[Response] = (response: Response) => {
    val `type` = response.args.getClass.getSimpleName
    JsObject(
      Map(
        "type" -> JsString(`type`),
        "args" -> (implicitly[Writes[Out]] writes response.args),
      ),
    )
  }
}
