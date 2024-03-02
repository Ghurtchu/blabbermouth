package ws

import cats.effect.kernel.Concurrent
import cats.effect.std.Console
import cats.effect.{Ref, Temporal}
import cats.{Applicative, Monad, Parallel}
import cats.syntax.all._
import dev.profunktor.redis4cats.algebra.SortedSetCommands
import dev.profunktor.redis4cats.effects.{Score, ScoreWithValue}
import fs2.Pipe
import fs2.concurrent.Topic
import redis.PubSubMessage
import redis.PubSubMessage._
import ws.Message.Out
import ws.Message.Out.{ChatHistory, UserLeft}
import ws.Message.Out.codecs.wul

import scala.concurrent.duration.DurationInt

trait WsConnectionClosedAction[F[_]] {
  def react(topic: Topic[F, Message], chatId: String, pingPong: Option[PingPong]): F[Unit]
}

object WsConnectionClosedAction {

  def empty[F[_]: Applicative]: WsConnectionClosedAction[F] =
    (_, _, _) => ().pure[F]

  def of[F[_]: Monad: Console: Temporal: Concurrent: Parallel](
    chatHistoryRef: Ref[F, Map[String, ChatHistory]],
    redisClient: redis.RedisClient[F],
    redisPublisher: redis.RedisPublisher[F],
  ): WsConnectionClosedAction[F] = new WsConnectionClosedAction[F] {

    // TODO: This logic can be refined..
    // TODO: Draw diagrams with timestamps to understand all the cases
    def react(topic: Topic[F, Message], chatId: String, pingPong: Option[PingPong]): F[Unit] =
      pingPong match {
        // both of them were joined at some point
        case Some(PingPong(Some(userT), Some(supportT))) =>
          if (userT.isBefore(supportT))
            userLeftChat[F](topic, chatId, chatHistoryRef, redisClient, redisPublisher)
          else
            Console[F].println("Support left the chat") *>
              topic.publish1(Out.SupportLeft(chatId)).void

        // only user has joined, so if WS get closed it means that only user has left :)
        case Some(PingPong(Some(_), None)) =>
          userLeftChat[F](topic, chatId, chatHistoryRef, redisClient, redisPublisher)

        // if PingPong is None it means that User hasn't even sent one "pong" response to WS and immediately left
        case None =>
          userLeftChat[F](topic, chatId, chatHistoryRef, redisClient, redisPublisher)

        // in any other cases it's only possible that User leaves the chat, not the Support
        case _ => userLeftChat[F](topic, chatId, chatHistoryRef, redisClient, redisPublisher)
      }
  }

  private def userLeftChat[F[_]: Monad: Console: Concurrent: Parallel](
    topic: Topic[F, Message],
    chatId: String,
    chatHistory: Ref[F, Map[String, ChatHistory]],
    redisClient: redis.RedisClient[F],
    redisPublisher: redis.RedisPublisher[F],
  ): F[Unit] = for {
    _ <- Console[F].println("User left the chat")
    maybeChatHistory <- chatHistory.get.map(_.get(chatId))
    _ <- maybeChatHistory.traverse_ { case ChatHistory(user: domain.User, _) =>
      (
        Console[F].println(s"setting status to inactive for user: ${user.username}, userId: ${user.userId}") *>
          redisClient.send(key = "users", score = 1, message = user.toJson).void,
        Console[F].println("sending UserLeft message to client") *>
          topic.publish1(Out.UserLeft(chatId)).void,
        Console[F].println("publishing UserLeft message to Redis Pub/Sub") *> fs2.Stream
          .emit(PubSubMessage[UserLeft]("UserLeft", UserLeft(chatId)).toJson)
          .through(redisPublisher.pipe)
          .compile
          .drain,
      ).parTupled.void
    }
  } yield ()

}
