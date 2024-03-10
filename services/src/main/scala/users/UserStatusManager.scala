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
      val (users, pending, inactive) = ("users", 0, 1)

      override def setPending(message: String): F[Unit] =
        underlying.send(
          key = users,
          score = pending,
          message = message,
        )

      override def setInactive(message: String): F[Unit] =
        underlying.send(
          key = users,
          score = inactive,
          message = message,
        )
    }

}
