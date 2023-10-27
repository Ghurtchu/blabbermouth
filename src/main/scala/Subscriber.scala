import cats.effect._
import com.comcast.ip4s.IpLiteralSyntax
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.{RedisChannel, RedisCodec}
import dev.profunktor.redis4cats.pubsub.PubSub
import dev.profunktor.redis4cats.effect.Log.NoOp.instance
import fs2.{Pipe, Stream}
import fs2.concurrent.Topic
import org.http4s.Method.GET
import org.http4s.dsl.io.{Ok, Root}
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.HttpRoutes

/** subscribes to Redis pub/sub and forwards messages to UI via Server Sent Events
  */
object Subscriber extends IOApp.Simple {

  val redisChannel: RedisChannel[String] = RedisChannel("joins")
  val stringCodec = RedisCodec.Utf8
  def webSocketApp(
    redisStream: Stream[IO, String],
    topic: Topic[IO, String],
    wsb: WebSocketBuilder2[IO],
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case GET -> Root / "joins" =>
      val receive: Pipe[IO, WebSocketFrame, Unit] = topic.publish.compose[Stream[IO, WebSocketFrame]](_.collect {
        case WebSocketFrame.Text(userId, _) => userId
      })

      val send: Stream[IO, WebSocketFrame] = topic.subscribe(10).flatMap { userId =>
        redisStream.filter(_ != userId).map(WebSocketFrame.Text(_))
      }

      wsb.build(
        send = send,
        receive = receive,
      )
    }
  override def run: IO[Unit] = (for {
    topic <- Resource.eval(Topic[IO, String])
    redisClient <- RedisClient[IO].from("redis://localhost")
    pubSub <- PubSub.mkPubSubConnection[IO, String, String](redisClient, stringCodec)
    subscribeStream: Stream[IO, String] = pubSub.subscribe(redisChannel)
    _ <- EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"9001")
      .withHttpWebSocketApp(webSocketApp(subscribeStream, topic, _).orNotFound)
      .build

  } yield ()).useForever

}
