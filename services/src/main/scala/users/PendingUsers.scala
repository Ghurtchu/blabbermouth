package users

import cats.{Applicative, Functor}
import cats.syntax.applicative._
import cats.syntax.functor._
import json.syntax.JsonReadsSyntax
import redis.RedisClient

/** Loads users with "pending" statuses from Redis.
  */
trait PendingUsers[F[_]] {
  def load: F[List[domain.User]]
}

object PendingUsers {

  def empty[F[+_]: Applicative]: PendingUsers[F] = new PendingUsers[F] {
    def load: F[List[domain.User]] = Nil.pure[F]
  }

  def of[F[_]: Functor](redisClient: RedisClient[F]): PendingUsers[F] = new PendingUsers[F] {
    def load: F[List[domain.User]] =
      redisClient
        .range(
          key = "users",
          start = 0,
          end = 0,
        )
        .map(_.flatMap(_.asOpt[domain.User]).reverse)
  }
}
