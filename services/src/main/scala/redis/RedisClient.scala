package redis

import cats.effect.Resource
import cats.effect.implicits.effectResourceOps
import cats.effect.kernel.Sync
import cats.{Applicative, Functor}
import cats.syntax.functor._
import cats.syntax.applicative._
import dev.profunktor.redis4cats.algebra.SortedSetCommands
import dev.profunktor.redis4cats.effects.{Score, ScoreWithValue, ZRange}
import io.lettuce.core.ZAddArgs

/** Convenient wrapper for redis client
  */
trait RedisClient[F[_]] {

  def send(
    key: String,
    score: Double,
    message: String,
    args: Option[ZAddArgs] = None,
  ): F[Unit]

  def range(
    key: String,
    start: Int,
    end: Int,
  ): F[List[String]]
}

object RedisClient {

  def empty[F[+_]: Applicative]: RedisClient[F] = new RedisClient[F] {
    def send(
      key: String,
      score: Double,
      message: String,
      args: Option[ZAddArgs],
    ): F[Unit] = ().pure[F]

    def range(
      key: String,
      start: Int,
      end: Int,
    ): F[List[String]] = Nil.pure[F]
  }

  def of[F[_]: Sync](
    sortedSetCommands: SortedSetCommands[F, String, String],
  ): RedisClient[F] = new RedisClient[F] {
    def send(
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

    def range(
      key: String,
      start: Int,
      end: Int,
    ): F[List[String]] =
      sortedSetCommands.zRangeByScore[Int](
        key = key,
        range = ZRange(start = start, end = end),
        limit = None,
      )
  }

  def make[F[_]: Sync](
    sortedSetCommands: SortedSetCommands[F, String, String],
  ): Resource[F, RedisClient[F]] =
    Sync[F].delay(of[F](sortedSetCommands)).toResource
}
