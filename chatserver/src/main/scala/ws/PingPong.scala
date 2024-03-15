package ws

import java.time.Instant

/** Groups the "pong" response timestamps for user and support.
  */
case class PingPong(userTimeStamp: Option[Instant], supportTimestamp: Option[Instant])

object PingPong {

  def initUser(t: Instant): PingPong =
    PingPong(userTimeStamp = Some(t), supportTimestamp = None)

  def initSupport(t: Instant): PingPong =
    PingPong(userTimeStamp = None, supportTimestamp = Some(t))
}
