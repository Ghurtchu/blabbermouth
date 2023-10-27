import cats.effect._
import com.comcast.ip4s.IpLiteralSyntax
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.{RedisChannel, RedisCodec}
import dev.profunktor.redis4cats.pubsub.PubSub
import dev.profunktor.redis4cats.effect.Log.NoOp.instance
import fs2.Stream
import fs2.concurrent.Topic
import org.http4s.Method.GET
import org.http4s.dsl.io.Root
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.HttpRoutes

/** subscribes to Redis pub/sub and forwards messages to UI via WebSockets
  */
object Subscriber extends IOApp.Simple {

  override val run = (for {
    topic <- Resource.eval(Topic[IO, String])
    redisClient <- RedisClient[IO].from("redis://localhost")
    pubSub <- PubSub.mkPubSubConnection[IO, String, String](redisClient, RedisCodec.Utf8)
    _ <- EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"9001")
      .withHttpWebSocketApp(webSocketApp(pubSub.subscribe(RedisChannel("joins")), topic, _).orNotFound)
      .build

  } yield ()).useForever
  def webSocketApp(
    redisStream: Stream[IO, String],
    topic: Topic[IO, String],
    wsb: WebSocketBuilder2[IO],
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case GET -> Root / "joins" =>
      wsb.build(
        send = topic
          .subscribe(10)
          .flatMap(userId => redisStream.filter(_ != userId).map(WebSocketFrame.Text(_))),
        receive = topic.publish
          .compose[Stream[IO, WebSocketFrame]](_.collect { case WebSocketFrame.Text(userId, _) => userId }),
      )
    }
}
