package users

import cats.Applicative
import cats.syntax.all._

/** Manages user statuses in Redis.
  *
  * Usage:
  *   - call [[setPending]] immediately when the user joins the system for the first time.
  *   - call [[setInactive]] when user closes the websocket connection or support joins the user's chat.
  */
trait UserStatusManager[F[_]] {
  def setPending(message: String): F[Unit]
  def setInactive(message: String): F[Unit]
}

object UserStatusManager {

  def empty[F[_]: Applicative]: UserStatusManager[F] = new UserStatusManager[F] {
    def setPending(message: String): F[Unit] = ().pure[F]
    def setInactive(message: String): F[Unit] = ().pure[F]
  }

  def of[F[_]](redisClient: redis.RedisClient[F]): UserStatusManager[F] =
    new UserStatusManager[F] {
      val Users = "users"
      val PendingScore = 0
      val InactiveScore = 1

      def setPending(message: String): F[Unit] =
        redisClient.send(
          key = Users,
          score = PendingScore,
          message = message,
        )

      def setInactive(message: String): F[Unit] =
        redisClient.send(
          key = Users,
          score = InactiveScore,
          message = message,
        )
    }
}
