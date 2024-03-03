package ws

import java.time.Instant

/** Groups the "pong" response timestamps for user and support.
  */
case class PingPong(
  userTimeStamp: Option[Instant],
  supportTimestamp: Option[Instant],
) {
  def updateUserTimestamp(t: Instant): PingPong =
    copy(userTimeStamp = Some(t))

  def updateSupportTimestamp(t: Instant): PingPong =
    copy(supportTimestamp = Some(t))
}

object PingPong {

  def initUser(t: Instant): PingPong =
    PingPong(userTimeStamp = Some(t), supportTimestamp = None)

  def initSupport(t: Instant): PingPong =
    PingPong(userTimeStamp = None, supportTimestamp = Some(t))
}
