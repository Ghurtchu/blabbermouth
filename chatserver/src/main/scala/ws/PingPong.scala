package ws

import java.time.Instant

/** Groups the "pong" response timestamps for user and support.
  */
case class PingPong(userTimeStamp: Option[Instant], supportTimestamp: Option[Instant])
