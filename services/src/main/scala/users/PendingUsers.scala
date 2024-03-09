package users

import cats.Applicative
import cats.syntax.applicative._
import redis.RedisClient

trait PendingUsersRetriever[F[_]] {
  def load: F[List[String]]
}

object PendingUsersRetriever {

  def empty[F[+_]: Applicative]: PendingUsersRetriever[F] = new PendingUsersRetriever[F] {
    override def load: F[List[String]] = Nil.pure[F]
  }

  def of[F[_]](redisClient: RedisClient[F]): PendingUsersRetriever[F] = new PendingUsersRetriever[F] {
    def load: F[List[String]] =
      redisClient.range(
        key = "users",
        start = 0,
        end = 0,
      )
  }
}
