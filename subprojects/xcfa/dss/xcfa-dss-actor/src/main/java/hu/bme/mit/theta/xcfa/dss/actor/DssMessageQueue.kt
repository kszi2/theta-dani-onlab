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
import java.util.concurrent.PriorityBlockingQueue

/**
 * The inbox of an actor, mirroring CPAchecker's `DssDefaultQueue`: `RESULT`, `EXCEPTION` and
 * `STATISTIC` messages overtake every pending analysis message; within the same priority, messages
 * are taken in the order they were created ([DssMessage.sequenceNumber]). This makes a worker react
 * to the final verdict right away instead of first working through a backlog of
 * postconditions/violation conditions that cannot change the outcome anymore.
 */
fun newDssMessageQueue(): BlockingQueue<DssMessage> =
  PriorityBlockingQueue(11, compareBy<DssMessage>({ it.priority }, { it.sequenceNumber }))

private val DssMessage.priority: Int
  get() =
    when (type) {
      DssMessageType.RESULT,
      DssMessageType.EXCEPTION,
      DssMessageType.STATISTIC -> 0
      DssMessageType.POST_CONDITION,
      DssMessageType.VIOLATION_CONDITION -> 1
    }
