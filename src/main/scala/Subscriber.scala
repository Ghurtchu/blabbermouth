import ChatServer.{JsonSyntax, WebSocketTextSyntax}
import cats.effect._
import com.comcast.ip4s.IpLiteralSyntax
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.{RedisChannel, RedisCodec}
import dev.profunktor.redis4cats.pubsub.PubSub
import dev.profunktor.redis4cats.effect.Log.NoOp.instance
import dev.profunktor.redis4cats.streams.{RedisStream, Streaming, data}
import fs2.Stream
import fs2.concurrent.Topic
import org.http4s.Method.GET
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.HttpRoutes
import play.api.libs.json._

import scala.concurrent.duration.DurationInt

/** subscribes to Redis pub/sub and forwards messages to UI via WebSockets
  */
object Subscriber extends IOApp.Simple {

  sealed trait Message

  case object Subscribe extends Message
  case class JoinUser(userId: String, username: String) extends Message
  implicit val joinUserFmt: Format[JoinUser] = Json.format[JoinUser]

  case class Request(`type`: String, args: Option[Message])
  implicit val readsRequest: Reads[Request] = json =>
    for {
      typ <- (json \ "type").validate[String]
      args <- typ match {
        case "Subscribe" => JsSuccess(None)
        case "JoinUser"  => joinUserFmt.reads(json("args")).map(Some(_))
        case _           => JsError("unrecognized request type")
      }
    } yield Request(typ, args)

  val src: Stream[IO, data.XReadMessage[String, String]] = (for {
    client: RedisClient <- Stream.resource[IO, RedisClient](RedisClient[IO].from("redis://localhost"))
    streaming <- RedisStream.mkStreamingConnection[IO, String, String](client, RedisCodec.Utf8)
    source = streaming.read(Set("joins"), chunkSize = 1)
  } yield source).flatten

  override val run =
    (for {
      messageFlow <- Resource.eval(Topic[IO, Message])
      redisPubSubConnection <- RedisClient[IO]
        .from("redis://localhost")
        .flatMap(PubSub.mkPubSubConnection[IO, String, String](_, RedisCodec.Utf8))
      redisStream = redisPubSubConnection.subscribe(RedisChannel("joins"))
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(host"0.0.0.0")
        .withPort(port"9001")
        .withHttpWebSocketApp(webSocketApp(redisStream, messageFlow, _, src).orNotFound)
        .withIdleTimeout(60.minutes)
        .build

    } yield ()).useForever
  def webSocketApp(
    redisPubSub: Stream[IO, String],
    messageFlow: Topic[IO, Message],
    wsb: WebSocketBuilder2[IO],
    redisStreams: Stream[IO, data.XReadMessage[String, String]],
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case GET -> Root / "joins" =>
      wsb.build(
        send = messageFlow
          .subscribe(10)
          .flatMap {
            case ju: JoinUser => Stream.eval(IO.pure(ju.asJson.asText))
            case Subscribe    => redisStreams.map(msg => WebSocketFrame.Text(msg.toString))
          },
        receive = messageFlow.publish
          .compose[Stream[IO, WebSocketFrame]](_.collect { case WebSocketFrame.Text(body, _) =>
            Json.parse(body).as[Request].args.getOrElse(Subscribe)
          }),
      )
    }
}
