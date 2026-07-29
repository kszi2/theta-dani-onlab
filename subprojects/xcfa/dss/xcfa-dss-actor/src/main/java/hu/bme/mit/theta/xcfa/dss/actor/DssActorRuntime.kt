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

import hu.bme.mit.theta.xcfa.dss.decomposition.Block
import hu.bme.mit.theta.xcfa.dss.decomposition.BlockGraph
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

private const val OBSERVER_ID = "__observer__"

private const val VISUALIZER_ID = "__visualizer__"
private const val VISUALIZER_JOIN_TIMEOUT_MILLIS = 10_000L
private const val BLOCK_THREAD_JOIN_TIMEOUT_MILLIS = 10_000L

/**
 * Builds one [DssBlockActor] per block in [blockGraph] (behavior from [behaviorFor]), plus the
 * observer and the [DssThreadMonitor], starts them, and blocks the calling thread until a verdict
 * is known. Mirrors CPAchecker's confirmed `MultithreadingDssExecutor`: one platform thread per
 * block, not virtual threads (real CPAchecker reaches 751 blocks this way - see the plan's open
 * questions on whether virtual threads are worth it given the thread-state-polling termination
 * design).
 *
 * [visualizationLog], when given, gets a [DssVisualizationActor] wired in as an additional
 * observer-role connection (build-order step 7) - off by default, like CPAchecker's
 * `DssVisualizationWorker` (created "only when debug mode enabled"). The caller's own list is
 * mutated directly; nothing needs to be read back out of a return value.
 */
fun runDssActors(
  blockGraph: BlockGraph,
  behaviorFor: (Block) -> DssBlockBehavior,
  pollIntervalMillis: Long = 10,
  visualizationLog: MutableList<DssLogEntry>? = null,
): DssResult {
  val active = ConcurrentHashMap.newKeySet<String>()
  val extraIds = if (visualizationLog != null) setOf(VISUALIZER_ID) else emptySet()
  val inboxes =
    (blockGraph.blocks.map { it.id } + OBSERVER_ID + extraIds).associateWith {
      LinkedBlockingQueue<DssMessage>()
    }
  val connectionsForBroadcaster =
    inboxes.mapValues { (id, queue) ->
      (if (id == OBSERVER_ID || id == VISUALIZER_ID) DssActorRole.OBSERVER
      else DssActorRole.BLOCK) to queue
    }
  val broadcaster = DssMessageBroadcaster(connectionsForBroadcaster)

  val observer =
    DssObserverActor(
      OBSERVER_ID,
      DssConnection(inboxes.getValue(OBSERVER_ID), broadcaster, active),
      blockGraph.blocks.size,
    )
  val blockActors =
    blockGraph.blocks.map { block ->
      DssBlockActor(
        block,
        DssConnection(inboxes.getValue(block.id), broadcaster, active),
        behaviorFor(block),
      )
    }
  val visualizer =
    visualizationLog?.let {
      DssVisualizationActor(
        VISUALIZER_ID,
        DssConnection(inboxes.getValue(VISUALIZER_ID), broadcaster, active),
        blockGraph.blocks.size,
        it,
      )
    }

  val blockThreads =
    blockActors.map { actor ->
      Thread(actor, actor.id).apply {
        isDaemon = true
        start()
      }
    }
  // Not part of threadsToMonitor, for the same reason the observer's own (borrowed, see below)
  // thread isn't: DssThreadMonitor only needs to watch actors that could still originate new work
  // on their own. Both the observer and the visualizer are pure sinks (their broadcast() asserts
  // it's never called with anything) - broadcaster.isEmpty() plus active already guarantee their
  // queues are fully drained before quiescence is declared, regardless of their own thread state.
  val visualizerThread =
    visualizer?.let {
      Thread(it, it.id).apply {
        isDaemon = true
        start()
      }
    }
  val monitor =
    DssThreadMonitor(blockThreads, broadcaster, active, pollIntervalMillis = pollIntervalMillis)
      .apply {
        isDaemon = true
        start()
      }

  try {
    val result = observer.observe()
    // The visualizer runs on its own thread, writing into the caller's own list - without waiting
    // for it to finish, there is no happens-before edge guaranteeing the caller (back on this
    // thread) sees a complete, correctly-ordered log afterward: observer.observe() returning only
    // means the *primary* observer's copy of RESULT/STATISTIC has all arrived, not the
    // visualizer's, since they read from independent queues. Bounded so a hypothetical stuck
    // visualizer can't hang the caller - it always finishes almost immediately in practice, since
    // it is fed the exact same handful of already-in-flight messages the primary observer just
    // finished draining.
    visualizerThread?.join(VISUALIZER_JOIN_TIMEOUT_MILLIS)
    return result
  } finally {
    // Only the SAFE path makes the monitor stop on its own; on the UNSAFE short-circuit path (and
    // on any error) it would otherwise spin forever, since every block thread it watches ends up
    // TERMINATED (not WAITING) rather than ever satisfying its quiescence check.
    monitor.interrupt()
    blockThreads.forEach { it.interrupt() }
    visualizerThread?.interrupt()
    // Block a bit longer for the interrupts to actually land: a block actor mid-solve is running
    // blocking native solver code that Thread.interrupt() cannot preempt, so without this a solver
    // thread can keep running - and keep holding its solver instance open - after this function has
    // already returned. That leftover thread then races the *next* xcfa-cli invocation's own
    // SolverManager.closeAll()/re-registration (e.g. the next test in the same JVM), which has been
    // observed to corrupt solver state process-wide. Joining here (bounded, so a genuinely stuck
    // solver can't hang the caller forever) keeps a completed runDssActors call meaning what it
    // says:
    // no DSS-owned thread is still running afterward.
    blockThreads.forEach { it.join(BLOCK_THREAD_JOIN_TIMEOUT_MILLIS) }
    monitor.join(BLOCK_THREAD_JOIN_TIMEOUT_MILLIS)
  }
}
