import cats.effect._
import cats.effect.std.Queue
import cats.implicits.{catsSyntaxApplicativeId, catsSyntaxOptionId, none}
import com.comcast.ip4s.IpLiteralSyntax
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.{RedisChannel, RedisCodec}
import dev.profunktor.redis4cats.pubsub.PubSub
import dev.profunktor.redis4cats.effect.Log.NoOp.instance
import fs2.{Pipe, Stream}
import json.syntax.{JsonReadsSyntax, JsonWritesSyntax}
import ws.{ClientWsMsg, Message, Queues, ServerWsMsg}
import ws.ClientWsMsg.ReadsClientWsMsg
import ws.Message.In.{JoinUser, LoadPendingUsers}
import ws.Message.In.codecs._
import org.http4s.Method.GET
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.HttpRoutes
import users.{PendingUsers, UserStatusManager}
import ws.Message.Out

import scala.concurrent.duration.DurationInt

/** subscribes to Redis pub/sub and forwards messages to UI via WebSockets
  */
object Subscriber extends IOApp.Simple {

  type PubSubMessage = String
  type Subscriber = Stream[IO, PubSubMessage]
  type WebSocketFrames = Pipe[IO, WebSocketFrame, Unit]

  // TODO: parse from config later
  val redisLocation = "redis://redis"

  private def mkSubscriber(
    redis: RedisClient,
    channel: RedisChannel[String],
  ): Resource[IO, Subscriber] =
    PubSub
      .mkPubSubConnection[IO, String, String](redis, RedisCodec.Utf8)
      .map(_.subscribe(channel))

  override val run = (for {
    queues <- Queues.make[IO]
    baseRedisClient <- RedisClient[IO].from(redisLocation)
    redisClient <- Redis[IO]
      .fromClient(client = baseRedisClient, codec = RedisCodec.Utf8)
      .flatMap(redis.RedisClient.make[IO])
    subscriber <- mkSubscriber(baseRedisClient, RedisChannel("joins"))
    pendingUsers = PendingUsers.of[IO](redisClient)
    userStatusManager = UserStatusManager.of[IO](redisClient)
    _ <- EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"9001")
      .withHttpWebSocketApp(webSocketApp(subscriber, queues, _, pendingUsers, userStatusManager).orNotFound)
      .withIdleTimeout(60.minutes)
      .build

  } yield ()).useForever

  def webSocketApp(
    subscriber: Subscriber,
    queues: Queues[IO],
    wsb: WebSocketBuilder2[IO],
    pendingUsers: PendingUsers[IO],
    userStatusManager: UserStatusManager[IO],
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case GET -> Root / "users" / supportId =>
      for {
        queueOpt <- queues.getOpt(supportId)
        (receive, queue) <- queueOpt.fold(init(queues, supportId))(queue => IO.delay(frames(queue), queue))
        send = Stream
          .fromQueueUnterminated(queue)
          .evalMapFilter {
            case LoadPendingUsers =>
              for {
                users <- IO.println(s"Loading pending users for support: $supportId") *> pendingUsers.load
                _ <- IO.println(s"Finished loading pending users for support: $supportId, users: $users")
              } yield Out.PendingUsers(users).some
            case ju: JoinUser =>
              for {
                _ <- IO.println(s"Attempting joining the user: $ju")
                _ <- IO.println(s"Setting status to 'inactive' in Redis for user: $ju")
                _ <- userStatusManager.setInactive(ju.toJson)
                _ <- IO.println(s"removing user from UI")
              } yield Out.RemoveUser(ju.userId).some
            case _ => none.pure[IO]
          }
          .collect { case o: Out => WebSocketFrame.Text(ServerWsMsg(o).toJson) }
          .merge {
            subscriber
              .evalMap { msg =>
                IO.println(s"$msg was consumed from Redis Pub/Sub") as
                  WebSocketFrame.Text(msg)
              }
          }
        ws <- wsb.build(send, receive)
      } yield ws
    }

  private def init(queues: Queues[IO], supportId: String): IO[(WebSocketFrames, Queue[IO, Message.In])] =
    for {
      queue <- Queue.circularBuffer[IO, Message.In](10)
      _ <- queues.update(supportId, queue)
    } yield frames(queue) -> queue

  private def frames(queue: Queue[IO, Message.In]): WebSocketFrames = _ evalMap {
    case WebSocketFrame.Text(msg, _) =>
      msg
        .as[ClientWsMsg]
        .fold(
          error => IO.println(s"could not deserialize $msg: $error"),
          wsMsg => queue.offer(wsMsg.args.getOrElse(LoadPendingUsers)),
        )
    case other => IO.println(s"received unexpected message: $other")
  }
}
