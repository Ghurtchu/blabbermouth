package redis

import play.api.libs.json._

case class PubSubMessage(`type`: String, args: PubSubMessage.Args)

object PubSubMessage {

  def from(args: PubSubMessage.Args): PubSubMessage =
    PubSubMessage(`type` = args.`type`, args = args)

  sealed trait Args {
    def `type`: String = this.getClass.getSimpleName
  }

  object Args {

    /** Sent when user joins the chat system and is waiting for the support specialist to join
      */
    case class UserPending(user: domain.User) extends Args

    /** Sent when user leaves the chat system
      */
    case class UserLeft(chatId: String) extends Args

    /** Sent when Support specialist joins the user's chat
      */
    case class SupportJoined(support: domain.Support) extends Args

    object codecs {
      implicit val wuj: Writes[UserPending] = Json.writes[domain.User].contramap(_.user)
      implicit val wul: Writes[UserLeft] = ul => play.api.libs.json.Writes.StringWrites.writes(ul.chatId)
      implicit val wsj: Writes[SupportJoined] = Json.writes[domain.Support].contramap(_.support)
      implicit val wa: Writes[Args] = {
        case uj: Args.UserPending   => wuj.writes(uj)
        case ul: Args.UserLeft      => wul.writes(ul)
        case sj: Args.SupportJoined => wsj.writes(sj)
      }
    }
  }

  import Args.codecs._

  implicit val wpsm: Writes[PubSubMessage] = (psm: PubSubMessage) =>
    JsObject(
      Map(
        "type" -> JsString(psm.`type`),
        "args" -> implicitly[Writes[Args]].writes(psm.args),
      ),
    )
}
