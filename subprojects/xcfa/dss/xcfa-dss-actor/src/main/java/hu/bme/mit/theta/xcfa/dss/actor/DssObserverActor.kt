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
 * Passively listens for the final verdict, mirroring CPAchecker's confirmed `DssObserverWorker`.
 * Does no routing of its own - it never has to, since it never produces a response. Shuts down once
 * a `RESULT` (or `EXCEPTION`) has arrived *and* every block has reported its own `STATISTIC`
 * message, which every block sends exactly once, right after receiving the `RESULT`/`EXCEPTION`
 * that was broadcast to everyone (see [DssBlockActor]).
 *
 * Both conditions are checked independently of arrival order: `broadcastToAll` is not atomic across
 * the queues it fans out to, so a block's own `STATISTIC` response (itself broadcast to everyone,
 * including the observer) can genuinely reach the observer's queue before the observer's own copy
 * of the `RESULT` that triggered it - a race this stub-worker setup hits often enough in practice
 * to have surfaced it directly (fast, do-nothing workers barely give the original broadcast time to
 * reach every queue before a recipient reacts and re-broadcasts). Requiring both, checked after
 * either arrives, is enough on its own - no message ordering needs to be enforced.
 */
class DssObserverActor(
  id: String,
  override val connection: DssConnection,
  private val numberOfBlocks: Int,
) : DssWorker(id) {

  private var shutdown = false
  private var result: DssResult? = null
  private var errorMessage: String? = null
  private val statsReceivedFrom = mutableSetOf<String>()

  override fun shutdownRequested(): Boolean = shutdown

  override fun processMessage(message: DssMessage): Collection<DssMessage> {
    when (message.type) {
      DssMessageType.RESULT -> result = (message as DssResultMessage).result
      DssMessageType.EXCEPTION -> {
        errorMessage = (message as DssExceptionMessage).error.message
        shutdown = true
      }
      DssMessageType.STATISTIC -> statsReceivedFrom.add(message.senderId)
      DssMessageType.POST_CONDITION,
      DssMessageType.VIOLATION_CONDITION -> {}
    }
    if (result != null && statsReceivedFrom.size >= numberOfBlocks) {
      shutdown = true
    }
    return emptyList()
  }

  override fun broadcast(messages: Collection<DssMessage>) {
    check(messages.isEmpty()) { "Observer never produces a response, but got: $messages" }
  }

  /** Blocks the calling thread until a final verdict is known. */
  fun observe(): DssResult {
    run()
    return verdict()
  }

  /**
   * Returns the verdict accumulated so far, throwing the same way [observe] does if no
   * `RESULT`/`EXCEPTION` has been seen yet. Exposed separately from [observe] so a non-threaded
   * executor that drives this actor's [processMessage] directly (without [run]) can retrieve the
   * same verdict once it has independently determined [shutdownRequested] is true.
   */
  fun verdict(): DssResult {
    errorMessage?.let { throw IllegalStateException(it) }
    return result ?: error("Analysis finished but no result is present.")
  }
}
