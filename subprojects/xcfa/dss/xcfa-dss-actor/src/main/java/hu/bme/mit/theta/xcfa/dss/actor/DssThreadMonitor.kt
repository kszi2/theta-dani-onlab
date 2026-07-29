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

/**
 * Detects the SAFE case by quiescence polling, mirroring CPAchecker's confirmed `DssThreadMonitor`
 * (plan §2) - deliberately *not* the SCC/per-block-fixpoint bookkeeping rev. 1 of this plan
 * originally guessed at. Every [pollIntervalMillis], checks whether every thread in
 * [threadsToMonitor] is parked (`WAITING`/`TIMED_WAITING`), every queue behind [broadcaster] is
 * empty, and [active] is empty (closing the race where a thread has just dequeued its last message
 * but hasn't reacted to it yet - see [DssWorker]). When all three hold at once, the network can
 * never produce another message on its own, so it broadcasts `RESULT(SAFE)` to everyone and stops.
 * Handles cyclic block graphs (e.g. loop bodies) for free, since quiescence does not care about
 * graph structure at all.
 */
class DssThreadMonitor(
  private val threadsToMonitor: List<Thread>,
  private val broadcaster: DssMessageBroadcaster,
  private val active: MutableSet<String>,
  private val id: String = "dss-monitor",
  private val pollIntervalMillis: Long = 10,
) : Thread(id) {

  override fun run() {
    while (true) {
      val allWaiting =
        threadsToMonitor.all { it.state == State.WAITING || it.state == State.TIMED_WAITING }

      if (allWaiting && broadcaster.isEmpty() && active.isEmpty()) {
        broadcaster.broadcastToAll(DssResultMessage(id, DssResult.SAFE))
        return
      }

      try {
        sleep(pollIntervalMillis)
      } catch (e: InterruptedException) {
        broadcaster.broadcastToAll(DssExceptionMessage(id, e))
        currentThread().interrupt()
        return
      }
    }
  }
}
