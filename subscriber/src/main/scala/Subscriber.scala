import cats.effect._
import com.comcast.ip4s.IpLiteralSyntax
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.algebra.SortedSetCommands
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.{RedisChannel, RedisCodec}
import dev.profunktor.redis4cats.pubsub.PubSub
import dev.profunktor.redis4cats.effect.Log.NoOp.instance
import dev.profunktor.redis4cats.effects.{Score, ScoreWithValue, ZRange}
import fs2.Stream
import fs2.concurrent.Topic
import ws.{WsMessage, WsRequestBody}
import ws.WsRequestBody.rr
import ws.WsMessage.In.{JoinUser, Load}
import ws.WsMessage.In.codecs._
import org.http4s.Method.GET
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.HttpRoutes
import play.api.libs.json._
import scala.util.chaining._

import scala.concurrent.duration.DurationInt

/** subscribes to Redis pub/sub and forwards messages to UI via WebSockets
  */
object Subscriber extends IOApp.Simple {

  type PubSubMessage = String
  type Subscriber = Stream[IO, PubSubMessage]

  val redisLocation = "redis://redis"

  val redis: Resource[IO, SortedSetCommands[IO, String, String]] =
    RedisClient[IO].from(redisLocation).flatMap(Redis[IO].fromClient(_, RedisCodec.Utf8))

  private def mkTopic[A]: Resource[IO, Topic[IO, A]] =
    Resource.eval(Topic[IO, A])

  private def mkRedis(redisLocation: String): Resource[IO, RedisClient] =
    RedisClient[IO].from(redisLocation)

  private def mkSubscriber(
    redis: RedisClient,
    channel: RedisChannel[String],
  ): Resource[IO, Subscriber] =
    PubSub
      .mkPubSubConnection[IO, String, String](redis, RedisCodec.Utf8)
      .map(_ subscribe channel)

  override val run = (for {
    topic <- mkTopic[WsMessage]
    redis <- mkRedis("redis://redis")
    subscriber <- mkSubscriber(redis, RedisChannel("joins"))
    _ <- EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"9001")
      .withHttpWebSocketApp(webSocketApp(subscriber, topic, _).orNotFound)
      .withIdleTimeout(60.minutes)
      .build

  } yield ()).useForever
  def webSocketApp(
    subscriber: Subscriber,
    topic: Topic[IO, WsMessage],
    wsb: WebSocketBuilder2[IO],
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case GET -> Root / "users" =>
      wsb.build(
        send = topic
          .subscribe(10)
          .flatMap {
            case Load =>
              val pendingUsers: IO[Stream[IO, WebSocketFrame.Text]] = for {
                _ <- IO.println("Loading pending users...")
                users <- redis.use {
                  _.zRangeByScore[Int]("users", ZRange(0, 0), None)
                    .map(_.reverse.map(WebSocketFrame.Text(_)))
                    .flatTap(u => IO.println(s"Finished loading pending users: $u"))
                    .flatMap(frames => IO(Stream.emits(frames)))
                }
              } yield users

              Stream.eval(pendingUsers).flatten
            case ju: JoinUser =>
              val response: IO[WebSocketFrame.Text] = for {
                _ <- IO.println(s"Attempting joining the user: $ju")
                json = Json.prettyPrint(Json.toJson(ju))
                _ <- redis.use(_.zAdd("users", None, ScoreWithValue(Score(1), json)))
                removeUserFromClient = s"""{"type":"RemoveUser","args":{"userId":"${ju.userId}"}}"""
                _ <- IO.println(s"sending back $removeUserFromClient")
              } yield WebSocketFrame.Text(removeUserFromClient)

              Stream.eval(response)
          }
          .merge {
            subscriber
              .map { msg =>
                println(s"$msg was consumed from Redis Pub/Sub")
                WebSocketFrame.Text(msg)
              }
          },
        receive = topic.publish
          .compose[Stream[IO, WebSocketFrame]](_.collect { case WebSocketFrame.Text(body, _) =>
            println(s"received message: $body")
            Json
              .parse(body)
              .as[WsRequestBody]
              .tap(req => println(s"parsed message to: $req"))
              .pipe(_.args.getOrElse(Load))
          }),
      )
    }
}
