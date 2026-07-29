/*
 *  Copyright 2026 Budapest University of Technology and Economics
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package hu.bme.mit.theta.xcfa.dss.actor

/** One observed message, tagged with the wall-clock time it was processed at. */
data class DssLogEntry(val timestampMillis: Long, val message: DssMessage)

/**
 * An optional, observer-role actor for debugging (build-order step 7), mirroring CPAchecker's
 * confirmed `DssVisualizationWorker`: created only on request (never by default - see
 * [runDssActors]/[runDssActorsSequentially]), it receives every message exactly the way
 * [DssObserverActor] does (registered with [DssActorRole.OBSERVER], so it gets the same
 * `POST_CONDITION`/`VIOLATION_CONDITION` copies `DssBlockActor.broadcast` routes to the observer,
 * plus every `EXCEPTION`/`RESULT`/`STATISTIC`, which are routed to *all* connections regardless of
 * role) and keeps a timestamped [log] of everything it has seen, in receipt order.
 *
 * Deliberately a separate class from [DssObserverActor] rather than a shared base: the two have
 * different jobs (accumulating a verdict vs. recording a trace) and only coincidentally share a
 * similar shutdown condition (a `RESULT`/`EXCEPTION` has arrived *and* every block has reported its
 * own `STATISTIC` - see [DssObserverActor] for why both are required, independent of arrival
 * order). A shutdown condition is still needed here even though the log itself has no reason to
 * ever stop growing, so that both [runDssActors] and [runDssActorsSequentially] can wait for this
 * actor to finish alongside the primary observer, rather than leaking a thread (concurrent
 * executor) or hanging the dispatch loop (sequential executor) once the real verdict is already
 * known.
 *
 * [entries] defaults to a fresh list for standalone use, but both executors instead inject the
 * caller-supplied `visualizationLog` list directly - the same object the caller already holds a
 * reference to - so nothing needs to be copied out of this actor once the run concludes.
 */
class DssVisualizationActor(
  id: String,
  override val connection: DssConnection,
  private val numberOfBlocks: Int,
  private val entries: MutableList<DssLogEntry> = mutableListOf(),
) : DssWorker(id) {

  private var shutdown = false
  private var sawFinalMessage = false
  private val statsReceivedFrom = mutableSetOf<String>()

  /** Every message this actor has observed, in receipt order, oldest first. */
  val log: List<DssLogEntry>
    get() = entries

  override fun shutdownRequested(): Boolean = shutdown

  override fun processMessage(message: DssMessage): Collection<DssMessage> {
    entries.add(DssLogEntry(System.currentTimeMillis(), message))
    when (message.type) {
      DssMessageType.RESULT,
      DssMessageType.EXCEPTION -> sawFinalMessage = true
      DssMessageType.STATISTIC -> statsReceivedFrom.add(message.senderId)
      DssMessageType.POST_CONDITION,
      DssMessageType.VIOLATION_CONDITION -> {}
    }
    if (sawFinalMessage && statsReceivedFrom.size >= numberOfBlocks) {
      shutdown = true
    }
    return emptyList()
  }

  override fun broadcast(messages: Collection<DssMessage>) {
    check(messages.isEmpty()) { "The visualizer never produces a response, but got: $messages" }
  }
}
