package users

import cats.Applicative
import cats.syntax.all._

trait UserStatusManager[F[_]] {
  def setPending(message: String): F[Unit]
  def setInactive(message: String): F[Unit]
}

object UserStatusManager {

  def empty[F[_]: Applicative]: UserStatusManager[F] = new UserStatusManager[F] {
    def setPending(message: String): F[Unit] = ().pure[F]
    def setInactive(message: String): F[Unit] = ().pure[F]
  }

  def of[F[_]](underlying: redis.RedisClient[F]): UserStatusManager[F] =
    new UserStatusManager[F] {
      val Users = "users"
      val PendingScore = 0
      val InactiveScore = 1

      def setPending(message: String): F[Unit] =
        underlying.send(
          key = Users,
          score = PendingScore,
          message = message,
        )

      def setInactive(message: String): F[Unit] =
        underlying.send(
          key = Users,
          score = InactiveScore,
          message = message,
        )
    }
}
