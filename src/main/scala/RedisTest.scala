import cats.effect.{IO, IOApp, Resource}
import dev.profunktor.redis4cats._
import dev.profunktor.redis4cats.algebra.{SortedSetCommands, StringCommands}
import dev.profunktor.redis4cats.connection._
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.effect.Log.NoOp.instance
import dev.profunktor.redis4cats.effects._

object RedisTest extends IOApp.Simple {

  val redisResource: Resource[IO, SortedSetCommands[IO, String, String]] =
    RedisClient[IO]
      .from("redis://localhost")
      .flatMap(Redis[IO].fromClient(_, RedisCodec.Utf8))
  override def run: IO[Unit] = redisResource.use { redis =>
    for {
      _ <- redis.zAdd("users", None, ScoreWithValue(Score(0), "first"))
      a1 <- redis.zRangeByScore("users", ZRange(0, 0), None)
      _ <- IO.println(s"a1: $a1")
      _ <- redis.zAdd("users", None, ScoreWithValue(Score(1), "first"))
      newA1 <- redis.zRangeByScore("users", ZRange(0, 0), None)
      _ <- IO.println(s"new a1: $newA1")
      a2 <- redis.zRangeByScore("users", ZRange(1, 1), None)
      _ <- IO.println(s"a2: $a2")
    } yield ()
  }
}
