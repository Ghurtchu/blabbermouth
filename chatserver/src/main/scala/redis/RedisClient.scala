package redis

import cats.effect.Resource
import cats.effect.kernel.Sync
import cats.{Applicative, Functor}
import cats.syntax.functor._
import cats.syntax.applicative._
import dev.profunktor.redis4cats.algebra.SortedSetCommands
import dev.profunktor.redis4cats.effects.{Score, ScoreWithValue}
import io.lettuce.core.ZAddArgs

trait RedisClient[F[_]] {
  def send(
    key: String,
    score: Double,
    message: String,
    args: Option[ZAddArgs] = None,
  ): F[Unit]
}

object RedisClient {

  def empty[F[_]: Applicative]: RedisClient[F] = (_, _, _, _) => ().pure[F]

  def of[F[_]: Sync](
    sortedSetCommands: SortedSetCommands[F, String, String],
  ): Resource[F, RedisClient[F]] = {
    val redisClient = new RedisClient[F] {
      override def send(
        key: String,
        score: Double,
        message: String,
        args: Option[ZAddArgs] = None,
      ): F[Unit] =
        sortedSetCommands
          .zAdd(
            key = key,
            args = args,
            values = ScoreWithValue(Score(score), message),
          )
          .void
    }

    Resource.eval(Sync[F].delay(redisClient))
  }
}
