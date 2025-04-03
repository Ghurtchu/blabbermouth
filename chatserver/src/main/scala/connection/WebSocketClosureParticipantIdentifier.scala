package connection

import domain.ChatParticipant

/** determines which participant left the chat based on the PingPong timestamp values
  */
object WebSocketClosureParticipantIdentifier {
  def from(pingPong: PingPong): Option[ChatParticipant] =
    pingPong match {
      case PingPong(Some(userT), Some(supportT)) =>
        Some(if (userT.isBefore(supportT)) ChatParticipant.User else ChatParticipant.Support)
      case PingPong(Some(_), None) => Some(ChatParticipant.User)
      case PingPong(None, Some(_)) => Some(ChatParticipant.Support)
      case PingPong(None, None)    => None
    }
}
