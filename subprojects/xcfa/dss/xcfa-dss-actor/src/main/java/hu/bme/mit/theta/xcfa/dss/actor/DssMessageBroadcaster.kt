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

import java.util.concurrent.BlockingQueue

/**
 * Whether an actor is a block worker or the (single) observer. Mirrors CPAchecker's confirmed
 * `DssCommunicationEntity`.
 */
enum class DssActorRole {
  BLOCK,
  OBSERVER,
}

/**
 * Fans a [DssMessage] out to one or more actor queues. Routing is always targeted - by actor id or
 * by role - never a literal "push to every actor" broadcast; see [DssBlockActor] for why (plan §2:
 * broadcasting is targeted routing, not "push to every actor including self", contrary to a literal
 * reading of the paper's Algorithm 3).
 */
class DssMessageBroadcaster(
  connections: Map<String, Pair<DssActorRole, BlockingQueue<DssMessage>>>
) {

  private val queuesById: Map<String, BlockingQueue<DssMessage>> =
    connections.mapValues { (_, roleAndQueue) -> roleAndQueue.second }

  private val observerQueues: List<BlockingQueue<DssMessage>> =
    connections.values.filter { (role, _) -> role == DssActorRole.OBSERVER }.map { it.second }

  fun isEmpty(): Boolean = queuesById.values.all { it.isEmpty() }

  fun broadcastToIds(message: DssMessage, ids: Set<String>) {
    for (id in ids) {
      val queue = requireNotNull(queuesById[id]) { "No connection found for id: $id" }
      queue.add(message)
    }
  }

  fun broadcastToObserver(message: DssMessage) {
    for (queue in observerQueues) {
      queue.add(message)
    }
  }

  fun broadcastToAll(message: DssMessage) {
    for (queue in queuesById.values) {
      queue.add(message)
    }
  }
}
