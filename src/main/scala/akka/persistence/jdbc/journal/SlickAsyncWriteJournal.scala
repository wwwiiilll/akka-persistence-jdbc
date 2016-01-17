/*
 * Copyright 2015 Dennis Vriend
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.persistence.jdbc.journal

import akka.actor.Terminated
import akka.persistence.jdbc.dao.JournalDao
import akka.persistence.jdbc.journal.AllPersistenceIdsSubscriberRegistry.AllPersistenceIdsSubscriberTerminated
import akka.persistence.jdbc.serialization.SerializationFacade
import akka.persistence.journal.AsyncWriteJournal
import akka.persistence.{ AtomicWrite, PersistentRepr }
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

object JdbcJournal {
  final val Identifier = "jdbc-journal"

  //  final case class SubscribePersistenceId(persistenceId: String)
  //  final case class EventAppended(persistenceId: String)

  case object AllPersistenceIdsRequest
  final case class AllPersistenceIdsResponse(allPersistenceIds: Set[String])
  final case class PersistenceIdAdded(persistenceId: String)
}

trait SlickAsyncWriteJournal extends AsyncWriteJournal with AllPersistenceIdsSubscriberRegistry {

  def journalDao: JournalDao

  implicit def mat: Materializer

  implicit def ec: ExecutionContext

  def serializationFacade: SerializationFacade

  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] = {
    val persistenceIdsInNewSetOfAtomicWrites = messages.map(_.persistenceId).toList
    for {
      xs ← journalDao.allPersistenceIdsSource.runFold(List.empty[String]) {
        case (c, pid) ⇒ if (persistenceIdsInNewSetOfAtomicWrites.contains(pid)) c :+ pid else c
      }
      xy ← Source.fromIterator(() ⇒ messages.iterator)
        .via(serializationFacade.serialize)
        .via(journalDao.writeFlow)
        .via(addAllPersistenceIdsFlow(xs))
        .map(_.map(_ ⇒ ()))
        .runFold(List.empty[Try[Unit]])(_ :+ _)
    } yield xy
  }

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] =
    journalDao.delete(persistenceId, toSequenceNr)

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] =
    journalDao.highestSequenceNr(persistenceId, fromSequenceNr)

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(recoveryCallback: (PersistentRepr) ⇒ Unit): Future[Unit] =
    journalDao.messages(persistenceId, fromSequenceNr, toSequenceNr, max)
      .via(serializationFacade.deserializeRepr)
      .mapAsync(1)(deserializedRepr ⇒ Future.fromTry(deserializedRepr))
      .runForeach(recoveryCallback)

  def handleTerminated: Receive = {
    case Terminated(ref) ⇒
      self ! AllPersistenceIdsSubscriberTerminated(ref)
  }

  override def receivePluginInternal: Receive =
    handleTerminated.orElse(receiveAllPersistenceIdsSubscriber)
}