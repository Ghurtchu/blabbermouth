package ws

import play.api.libs.json.{Format, Json, Writes}

// supertype of all messages
sealed trait Message

object Message {

  // groups incoming messages from Client to Server
  sealed trait In extends Message
  // groups outgoing messages from Server to Client
  sealed trait Out extends Message

  object In {

    /** Used in case Client sends:
      *   - unrecognizable / malformed Json
      *   - any other thing that can't be parsed from contextually available Reads[ClientWsMsg]
      */
    case object UnrecognizedMessage extends In
    // sent from UI as soon as support joins so that backend loads pending users from Redis
    case object LoadPendingUsers extends In
    // sent from UI as soon as Support clicks to User to join them
    case class JoinUser(userId: String, username: String, chatId: String) extends In

    object codecs {
      implicit val fju: Format[JoinUser] = Json.format[JoinUser]
    }
  }

  object Out {
    // list of users read from Redis while processing LoadPendingUsers
    case class PendingUsers(users: List[domain.User]) extends Out
    // freshly joined user attempting to connect to support
    case class NewUser(userId: String, username: String, chatId: String) extends Out
    // message for FE to drop the user as soon as they leave
    case class RemoveUser(userId: String) extends Out

    // formats
    object codecs {
      implicit val wpu: Writes[PendingUsers] = Json.writes[PendingUsers]
      implicit val wnu: Writes[NewUser] = Json.writes[NewUser]
      implicit val wru: Writes[RemoveUser] = Json.writes[RemoveUser]
      implicit val wo: Writes[Out] = {
        case pu: PendingUsers => wpu writes pu
        case n: NewUser       => wnu writes n
        case ru: RemoveUser   => wru writes ru
      }
    }
  }
}
