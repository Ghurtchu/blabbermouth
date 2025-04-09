package ws

import cats.Functor
import cats.effect.implicits.effectResourceOps
import cats.syntax.all._
import cats.effect.{Ref, Resource}
import cats.effect.kernel.Concurrent
import cats.effect.std.Queue
import ws.Queues.{Key, State, Value}

/** Concurrent-safe data structure that stores [[cats.effect.std.Queue]] per [[ws.Queues.SupportId]] and exposes public methods for read/write
  * operations.
  */
final class Queues[F[_]: Functor](private val underlying: Ref[F, State[F]]) {

  def getOpt(key: Key): F[Option[Value[F]]] =
    underlying.get.map(_.get(key))

  def update(key: Key, value: Value[F]): F[Unit] =
    underlying.update(_.updated(key, value))
}

object Queues {
  type SupportId = String
  type Key = SupportId
  type Value[F[_]] = Queue[F, Message.In]
  type State[F[_]] = Map[Key, Value[F]]

  def make[F[_]: Concurrent: Functor]: Resource[F, Queues[F]] =
    of[F].toResource

  def of[F[_]: Concurrent: Functor]: F[Queues[F]] = for {
    qs <- Ref.of[F, State[F]](Map.empty)
  } yield new Queues(qs)
}
