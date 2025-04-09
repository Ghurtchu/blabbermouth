package redis

import play.api.libs.json._
import redis.PubSubMessage.Args

/** Representation of Redis pub/sub message */
case class PubSubMessage(`type`: String, args: Args)

object PubSubMessage {

  def from(args: Args): PubSubMessage =
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
    case class UserLeft(user: domain.User) extends Args

    /** Sent when Support specialist joins the user's chat
      */
    case class SupportJoined(support: domain.Support) extends Args

    object codecs {
      implicit val WritesUserPending: Writes[UserPending] = Json.writes[domain.User].contramap(_.user)
      implicit val WritesUserLeft: Writes[UserLeft] = Json.writes[domain.User].contramap(_.user)
      implicit val WritesSupportJoined: Writes[SupportJoined] = Json.writes[domain.Support].contramap(_.support)
      implicit val WritesArgs: Writes[Args] = {
        case up: Args.UserPending   => WritesUserPending.writes(up)
        case ul: Args.UserLeft      => WritesUserLeft.writes(ul)
        case sj: Args.SupportJoined => WritesSupportJoined.writes(sj)
      }
    }
  }

  import Args.codecs._

  implicit val WritesPubSubMessage: Writes[PubSubMessage] = (psm: PubSubMessage) =>
    JsObject(
      Map(
        "type" -> JsString(psm.`type`),
        "args" -> implicitly[Writes[Args]].writes(psm.args),
      ),
    )
}
