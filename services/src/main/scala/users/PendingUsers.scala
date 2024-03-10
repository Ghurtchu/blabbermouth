package users

import cats.{Applicative, Functor}
import cats.syntax.applicative._
import cats.syntax.functor._
import redis.RedisClient

trait PendingUsers[F[_]] {
  def load: F[List[String]]
}

object PendingUsers {

  def empty[F[+_]: Applicative]: PendingUsers[F] = new PendingUsers[F] {
    def load: F[List[String]] = Nil.pure[F]
  }

  def of[F[_]: Functor](redisClient: RedisClient[F]): PendingUsers[F] = new PendingUsers[F] {
    def load: F[List[String]] =
      redisClient
        .range(
          key = "users",
          start = 0,
          end = 0,
        )
        .map(_.reverse)
  }
}
