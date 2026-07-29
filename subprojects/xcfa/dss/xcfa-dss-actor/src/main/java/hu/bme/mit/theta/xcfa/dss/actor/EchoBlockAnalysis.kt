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
 * Stub [DssBlockBehavior] for validating the actor runtime's routing and termination detection
 * without any real analysis wired in (build-order step 4). On startup, and again the first time it
 * hears from each distinct sender, echoes exactly one message of the same kind back out; repeat
 * messages from an already-seen sender produce nothing. This is the simplest possible stand-in for
 * the real subsumption check (`CoverageOperator`/`ProceedOperator` in CPAchecker, plan §3) that
 * makes a real worker stop propagating once nothing new has arrived - without it, a block on a
 * cyclic part of the block graph (e.g. a loop body, which lists itself as both predecessor and
 * successor - see `xcfa-dss-decomposition`) would echo forever and the network would never reach
 * quiescence.
 *
 * @param originatesViolation whether this block should also emit a violation condition on startup,
 *   standing in for "this block's own analysis found a specification violation reachable from no
 *   incoming information" - a real worker only does this when its local CEGAR run actually finds
 *   one; here it is just configured per test scenario.
 */
class EchoBlockAnalysis(
  private val blockId: String,
  private val originatesViolation: Boolean = false,
) : DssBlockBehavior {

  private val seenPostConditionSenders = mutableSetOf<String>()
  private val seenViolationConditionSenders = mutableSetOf<String>()

  override fun initialMessages(): Collection<DssMessage> = buildList {
    add(DssPostConditionMessage(blockId))
    if (originatesViolation) add(DssViolationConditionMessage(blockId))
  }

  override fun onPostCondition(message: DssPostConditionMessage): Collection<DssMessage> =
    if (seenPostConditionSenders.add(message.senderId)) listOf(DssPostConditionMessage(blockId))
    else emptyList()

  override fun onViolationCondition(message: DssViolationConditionMessage): Collection<DssMessage> =
    if (seenViolationConditionSenders.add(message.senderId))
      listOf(DssViolationConditionMessage(blockId))
    else emptyList()
}
