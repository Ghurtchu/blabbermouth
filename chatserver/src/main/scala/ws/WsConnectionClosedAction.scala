package ws

import cats.effect.kernel.Concurrent
import cats.effect.std.Console
import cats.effect.{IO, Ref, Temporal}
import cats.{Applicative, Monad, Parallel}
import cats.syntax.all._
import dev.profunktor.redis4cats.algebra.SortedSetCommands
import dev.profunktor.redis4cats.effects.{Score, ScoreWithValue}
import domain.User
import fs2.Pipe
import fs2.concurrent.Topic
import redis.PubSubMessage
import redis.PubSubMessage._
import ws.Message.Out
import ws.Message.Out.{ChatHistory, UserLeft}
import ws.Message.Out.codecs.wul

import scala.concurrent.duration.DurationInt

trait WsConnectionClosedAction[F[_]] {
  def react(
    topic: Topic[F, Message],
    chatId: String,
    pingPong: PingPong,
    chatHistoryRef: Ref[F, Map[String, ChatHistory]],
    redisCmdClient: SortedSetCommands[F, String, String],
    publisher: Pipe[F, String, Unit],
  ): F[Unit]
}

object WsConnectionClosedAction {

  def empty[F[_]: Applicative]: WsConnectionClosedAction[F] = ???

  def of[F[_]: Monad: Console: Temporal: Concurrent: Parallel]: WsConnectionClosedAction[F] = new WsConnectionClosedAction[F] {

    def react(
      topic: Topic[F, Message],
      chatId: String,
      pingPong: PingPong,
      chatHistoryRef: Ref[F, Map[String, ChatHistory]],
      redisCmdClient: SortedSetCommands[F, String, String],
      publisher: Pipe[F, String, Unit],
    ): F[Unit] =
      for {
        _ <- Console[F].println("WS connection was closed")
        // semantically blocks to make sure that we check the latest pong from User & Support later
        _ <- Temporal[F].sleep(5.seconds)
        _ <- pingPong match {
          // both of them were joined at some point
          case PingPong(Some(userT), Some(supportT)) =>
            if (userT.isBefore(supportT))
              userLeftChat[F](topic, chatId, chatHistoryRef, redisCmdClient, publisher)
            else
              Console[F].println("Support left the chat") *>
                topic.publish1(Out.SupportLeft(chatId)).void

          // only user has joined, so if WS get closed it means that only user has left :)
          case PingPong(Some(_), None) =>
            userLeftChat[F](topic, chatId, chatHistoryRef, redisCmdClient, publisher)

          // in any other cases it's only possible that User leaves the chat, not the Support
          case _ => userLeftChat[F](topic, chatId, chatHistoryRef, redisCmdClient, publisher)
        }
      } yield ()
  }

  private def userLeftChat[F[_]: Monad: Console: Concurrent: Parallel](
    topic: Topic[F, Message],
    chatId: String,
    chatHistory: Ref[F, Map[String, ChatHistory]],
    redisCmdClient: SortedSetCommands[F, String, String],
    publisher: Pipe[F, String, Unit],
  ): F[Unit] = for {
    _ <- Console[F].println("User left the chat")
    maybeChatHistory <- chatHistory.get.map(_.get(chatId))
    _ <- maybeChatHistory.traverse_ { case ChatHistory(user: domain.User, _) =>
      (
        Console[F].println(s"setting status to inactive for user with userId: ${user.userId}") *>
          redisCmdClient.zAdd("users", None, ScoreWithValue(Score(1), user.toJson)).void,
        Console[F].println("sending UserLeft message to client") *>
          topic.publish1(Out.UserLeft(chatId)).void,
        Console[F].println("publishing UserLeft message to Redis Pub/Sub") *> fs2.Stream
          .emit(PubSubMessage[UserLeft]("UserLeft", UserLeft(chatId)).toJson)
          .through(publisher)
          .compile
          .drain,
      ).parTupled.void
    }
  } yield ()

}
