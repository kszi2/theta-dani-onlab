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
 * What a [DssBlockActor] does in response to incoming analysis messages, decoupled from the
 * routing/termination machinery around it - the seam build-order step 5 will plug real per-block
 * CEGAR (CPAchecker's confirmed `DssBlockAnalysis`: `runInitialAnalysis`/`storePrecondition`/
 * `analyzePrecondition`/`storeViolationCondition`/`analyzeViolationCondition`) into, in place of
 * [EchoBlockAnalysis]. Deliberately not shaped after that two-phase store-then-analyze API yet -
 * this step only needs *a* pluggable behavior to validate the actor runtime against, not the right
 * one; guessing its final shape now would just be something to undo later.
 */
interface DssBlockBehavior {

  /**
   * Messages a block emits on startup, before receiving anything - e.g. its own summary computed
   * from no incoming information (or a violation condition, if it can locally reach a target).
   */
  fun initialMessages(): Collection<DssMessage>

  fun onPostCondition(message: DssPostConditionMessage): Collection<DssMessage>

  fun onViolationCondition(message: DssViolationConditionMessage): Collection<DssMessage>
}
