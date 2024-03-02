package redis

import cats.effect.Resource
import cats.effect.implicits.effectResourceOps
import cats.effect.kernel.Sync
import fs2.Pipe

final class RedisPublisher[F[_]](val pipe: Pipe[F, String, Unit]) extends AnyVal

object RedisPublisher {
  def empty[F[_]]: RedisPublisher[F] = new RedisPublisher[F](_ => fs2.Stream.empty)

  def of[F[_]: Sync](stream: Pipe[F, String, Unit]): F[RedisPublisher[F]] =
    Sync[F].delay(new RedisPublisher[F](stream))

  def make[F[_]: Sync](stream: Pipe[F, String, Unit]): Resource[F, RedisPublisher[F]] =
    of[F](stream).toResource
}
