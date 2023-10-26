import cats.effect._
import com.comcast.ip4s.IpLiteralSyntax
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.{RedisChannel, RedisCodec}
import dev.profunktor.redis4cats.pubsub.PubSub
import dev.profunktor.redis4cats.effect.Log.NoOp.instance
import fs2.Stream
import org.http4s.Method.GET
import org.http4s.dsl.io.{Ok, Root}
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`Content-Type`
import org.http4s.{HttpRoutes, MediaType}

/** subscribes to Redis pub/sub and forwards messages to UI via Server Sent Events
  */
object Subscriber extends IOApp.Simple {

  val redisChannel: RedisChannel[String] = RedisChannel("joins")
  val stringCodec = RedisCodec.Utf8
  def routes(redisStream: Stream[IO, String]): HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root / "joins" =>
    Ok(redisStream, `Content-Type`(MediaType.`text/event-stream`))
  }
  override def run: IO[Unit] = (for {
    redisClient <- RedisClient[IO].from("redis://localhost")
    pubSub <- PubSub.mkPubSubConnection[IO, String, String](redisClient, stringCodec)
    subscribeStream: Stream[IO, String] = pubSub.subscribe(redisChannel)
    _ <- EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"9001")
      .withHttpApp(routes(subscribeStream).orNotFound)
      .build

  } yield ()).useForever

}
