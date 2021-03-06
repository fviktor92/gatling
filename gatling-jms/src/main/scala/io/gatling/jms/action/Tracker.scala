/**
 * Copyright 2011-2017 GatlingCorp (http://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.jms.action

import javax.jms.Message

import scala.collection.mutable
import scala.concurrent.duration._

import io.gatling.commons.stats.{ KO, OK, Status }
import io.gatling.commons.util.ClockSingleton.nowMillis
import io.gatling.commons.validation.Failure
import io.gatling.core.action.Action
import io.gatling.core.akka.BaseActor
import io.gatling.core.check.Check
import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.session.Session
import io.gatling.core.stats.StatsEngine
import io.gatling.core.stats.message.ResponseTimings
import io.gatling.jms._

import akka.actor.Props

/**
 * Advise actor a message was sent to JMS provider
 */
case class MessageSent(
  matchId:      String,
  sent:         Long,
  replyTimeout: Long,
  checks:       List[JmsCheck],
  session:      Session,
  next:         Action,
  requestName:  String
)

/**
 * Advise actor a response message was received from JMS provider
 */
case class MessageReceived(
  matchId:  String,
  received: Long,
  message:  Message
)

case object TimeoutScan

object Tracker {
  def props(statsEngine: StatsEngine, configuration: GatlingConfiguration) = Props(new Tracker(statsEngine, configuration))
}

/**
 * Bookkeeping actor to correlate request and response JMS messages
 * Once a message is correlated, it publishes to the Gatling core DataWriter
 */
class Tracker(statsEngine: StatsEngine, configuration: GatlingConfiguration) extends BaseActor {

  private val sentMessages = mutable.HashMap.empty[String, MessageSent]
  private val timedOutMessages = mutable.ArrayBuffer.empty[MessageSent]
  private val replyTimeoutScanPeriod = configuration.jms.replyTimeoutScanPeriod milliseconds
  private var periodicTimeoutScanTriggered = false

  def triggerPeriodicTimeoutScan(): Unit =
    if (!periodicTimeoutScanTriggered) {
      periodicTimeoutScanTriggered = true
      scheduler.schedule(replyTimeoutScanPeriod, replyTimeoutScanPeriod) {
        self ! TimeoutScan
      }
    }

  def receive = {
    // message was sent; add the timestamps to the map
    case messageSent: MessageSent =>
      sentMessages += messageSent.matchId -> messageSent
      if (messageSent.replyTimeout > 0) {
        triggerPeriodicTimeoutScan()
      }

    // message was received; publish stats and remove from the hashmap
    case MessageReceived(matchId, received, message) =>
      // if key is missing, message was already acked and is a dup, or request timedout
      sentMessages.remove(matchId).foreach {
        case MessageSent(_, sent, _, checks, session, next, requestName) =>
          processMessage(session, sent, received, checks, message, next, requestName)
      }

    case TimeoutScan =>
      val now = nowMillis
      sentMessages.valuesIterator.foreach { message =>
        val replyTimeout = message.replyTimeout
        if (replyTimeout > 0 && (now - message.sent) > replyTimeout) {
          timedOutMessages += message
        }
      }

      for (MessageSent(matchId, sent, receivedTimeout, checks, session, next, requestName) <- timedOutMessages) {
        sentMessages.remove(matchId)
        executeNext(session.markAsFailed, sent, now, KO, next, requestName, Some(s"Reply timeout after $receivedTimeout ms"))
      }
      timedOutMessages.clear()
  }

  private def executeNext(
    session:     Session,
    sent:        Long,
    received:    Long,
    status:      Status,
    next:        Action,
    requestName: String,
    message:     Option[String]
  ) = {
    val timings = ResponseTimings(sent, received)
    statsEngine.logResponse(session, requestName, timings, status, None, message)
    next ! session.logGroupRequest(timings.responseTime, status).increaseDrift(nowMillis - received)
  }

  /**
   * Processes a matched message
   */
  private def processMessage(
    session:     Session,
    sent:        Long,
    received:    Long,
    checks:      List[JmsCheck],
    message:     Message,
    next:        Action,
    requestName: String
  ): Unit = {
    // run all the checks, advise the Gatling API that it is complete and move to next
    val (checkSaveUpdate, error) = Check.check(message, session, checks)
    val newSession = checkSaveUpdate(session)
    error match {
      case Some(Failure(errorMessage)) => executeNext(newSession.markAsFailed, sent, received, KO, next, requestName, Some(errorMessage))
      case _                           => executeNext(newSession, sent, received, OK, next, requestName, None)
    }
  }
}
