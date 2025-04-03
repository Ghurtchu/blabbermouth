package connection

import java.time.Instant

/** Wraps the "pong" response timestamps for user and support which is concurrently updated inside [[cats.effect.Ref]].
  */
final case class PingPong(userTimeStamp: Option[Instant], supportTimestamp: Option[Instant]) {
  def dropSupport: PingPong = copy(supportTimestamp = None)
  def dropUser: PingPong = copy(userTimeStamp = None)
}

object PingPong {

  def initUser(t: Instant): PingPong =
    PingPong(userTimeStamp = Some(t), supportTimestamp = None)

  def initSupport(t: Instant): PingPong =
    PingPong(userTimeStamp = None, supportTimestamp = Some(t))
}
