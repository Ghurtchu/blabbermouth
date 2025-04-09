package ws

import play.api.libs.json.{Format, Json, Writes}

/** Supertype for all messages. Used to parametrize [[cats.effect.std.Queue]]. [[cats.effect.std.Queue]] will be used
  */
sealed trait Message

object Message {

  /** Incoming messages from support UI
    */
  sealed trait In extends Message

  /** Outgoing messages to support UI
    */
  sealed trait Out extends Message

  object In {

    /** sent from UI as soon as support joins so that backend loads pending users from Redis */
    case object LoadPendingUsers extends In

    /** sent from UI as soon as Support clicks User button which initializes the live chat */
    case class JoinUser(userId: String, username: String, chatId: String) extends In

    object codecs {
      implicit val fju: Format[JoinUser] = Json.format[JoinUser]
    }
  }

  object Out {

    /** list of users fetched from Redis channel while processing [[ws.Message.In.LoadPendingUsers]] */
    case class PendingUsers(users: List[domain.User]) extends Out

    /** freshly joined user attempting to connect to support */
    case class NewUser(userId: String, username: String, chatId: String) extends Out

    /** sent to Support UI to drop the user as soon as they leave */
    case class RemoveUser(userId: String) extends Out

    object codecs {
      implicit val WritesPendingUsers: Writes[PendingUsers] = Json.writes[PendingUsers]
      implicit val WritesNewUser: Writes[NewUser] = Json.writes[NewUser]
      implicit val WritesRemoveUser: Writes[RemoveUser] = Json.writes[RemoveUser]
      implicit val WritesOut: Writes[Out] = {
        case pu: PendingUsers => WritesPendingUsers writes pu
        case nu: NewUser      => WritesNewUser writes nu
        case ru: RemoveUser   => WritesRemoveUser writes ru
      }
    }
  }
}
