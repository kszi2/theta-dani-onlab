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
import java.util.concurrent.LinkedBlockingQueue

private const val SEQUENTIAL_OBSERVER_ID = "__observer__"
private const val SEQUENTIAL_VISUALIZER_ID = "__visualizer__"
private const val SEQUENTIAL_EXECUTOR_ID = "sequential-executor"

/**
 * A deterministic, single-threaded stand-in for [runDssActors], mirroring the rationale behind
 * CPAchecker's confirmed `SINGLE_WORKER`/`SEQUENTIAL` executors (plan §2, build order step 6):
 * "deterministic, easiest to diff against Theta's existing single-threaded predicate CEGAR" before
 * trusting the concurrent one. Every [DssBlockActor]/[DssObserverActor] is built exactly as in
 * [runDssActors] - same routing, same termination rules, same [DssBlockBehavior] - so this is a
 * genuine alternative driver for the *same* actor code, not a separate reimplementation of the
 * protocol.
 *
 * The difference is entirely in how messages get delivered: no [Thread] per block, no
 * [DssThreadMonitor] polling thread state. Instead, this function itself repeatedly picks any actor
 * with a pending message and calls [DssWorker.processOneMessage] directly on the calling thread.
 * Global quiescence - the SAFE case - is detected the same way [DssThreadMonitor] detects it (every
 * queue empty), except trivially rather than by polling: since nothing runs concurrently with this
 * loop, "no queue has a pending message" already *is* "nothing can happen next", with no race to
 * guard against and no need for the `active` bookkeeping [DssThreadMonitor] needs to close one. The
 * UNSAFE case still short-circuits exactly as [DssBlockActor] already implements it (a root block's
 * own [DssBlockActor.broadcast] enqueues `RESULT(UNSAFE)` to everyone); this driver only ever needs
 * to originate `RESULT(SAFE)` itself.
 *
 * Because everything happens on one thread, this executor is also incidentally free of the Z3
 * concurrency hazard [runWorkerConfig][hu.bme.mit.theta.xcfa.dss.analysis.runWorkerConfig]'s global
 * lock works around (build-order step 5's finding) - useful as a lower-risk way to validate new
 * [DssBlockBehavior]s (e.g. against cyclic block graphs, step 6's actual goal) before trusting them
 * under real concurrency.
 */
fun runDssActorsSequentially(
  blockGraph: BlockGraph,
  behaviorFor: (Block) -> DssBlockBehavior,
  visualizationLog: MutableList<DssLogEntry>? = null,
): DssResult {
  val active = mutableSetOf<String>()
  val extraIds = if (visualizationLog != null) setOf(SEQUENTIAL_VISUALIZER_ID) else emptySet()
  val inboxes =
    (blockGraph.blocks.map { it.id } + SEQUENTIAL_OBSERVER_ID + extraIds).associateWith {
      LinkedBlockingQueue<DssMessage>()
    }
  val connectionsForBroadcaster =
    inboxes.mapValues { (id, queue) ->
      val role =
        if (id == SEQUENTIAL_OBSERVER_ID || id == SEQUENTIAL_VISUALIZER_ID) DssActorRole.OBSERVER
        else DssActorRole.BLOCK
      role to queue
    }
  val broadcaster = DssMessageBroadcaster(connectionsForBroadcaster)

  val observer =
    DssObserverActor(
      SEQUENTIAL_OBSERVER_ID,
      DssConnection(inboxes.getValue(SEQUENTIAL_OBSERVER_ID), broadcaster, active),
      blockGraph.blocks.size,
    )
  val blockActors =
    blockGraph.blocks.associate { block ->
      block.id to
        DssBlockActor(
          block,
          DssConnection(inboxes.getValue(block.id), broadcaster, active),
          behaviorFor(block),
        )
    }
  val visualizer =
    visualizationLog?.let {
      DssVisualizationActor(
        SEQUENTIAL_VISUALIZER_ID,
        DssConnection(inboxes.getValue(SEQUENTIAL_VISUALIZER_ID), broadcaster, active),
        blockGraph.blocks.size,
        it,
      )
    }
  val workers: Map<String, DssWorker> =
    blockActors +
      (SEQUENTIAL_OBSERVER_ID to observer) +
      listOfNotNull(visualizer).associateBy { it.id }

  blockActors.values.forEach { it.start() }

  // Keeps draining even after the observer itself has shut down, in case the visualizer's own
  // copy of the last STATISTIC message (or anything else) is still sitting unprocessed in its
  // separate queue - the observer and the visualizer reach their own shutdown conditions
  // independently, from independent queues, so one finishing first must not cut the other off.
  while (!observer.shutdownRequested() || inboxes.values.any { it.isNotEmpty() }) {
    val pending = inboxes.entries.firstOrNull { (_, queue) -> queue.isNotEmpty() }
    if (pending == null) {
      broadcaster.broadcastToAll(DssResultMessage(SEQUENTIAL_EXECUTOR_ID, DssResult.SAFE))
      continue
    }
    val message = pending.value.poll() ?: continue
    workers.getValue(pending.key).processOneMessage(message)
  }

  return observer.verdict()
}
