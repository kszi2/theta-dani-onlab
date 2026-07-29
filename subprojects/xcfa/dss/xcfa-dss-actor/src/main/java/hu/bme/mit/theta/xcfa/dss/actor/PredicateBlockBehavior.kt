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

import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolExprs.False
import hu.bme.mit.theta.core.type.booltype.BoolExprs.Or
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.xcfa.dss.analysis.XcfaChecker
import hu.bme.mit.theta.xcfa.dss.analysis.extractBlockXcfa
import hu.bme.mit.theta.xcfa.dss.analysis.globalAssumePredicatePrecision
import hu.bme.mit.theta.xcfa.dss.analysis.packPostcondition
import hu.bme.mit.theta.xcfa.dss.analysis.runWorkerConfig
import hu.bme.mit.theta.xcfa.dss.decomposition.Block
import hu.bme.mit.theta.xcfa.model.XCFA
import hu.bme.mit.theta.xcfa.model.XcfaProcedure

/**
 * The first real (non-stub) [DssBlockBehavior]: wires `xcfa-dss-analysis`'s block extraction and
 * pack/unpack primitives (build-order step 5) into the actor runtime (step 4), so real per-block
 * CEGAR drives postcondition/violation-condition propagation instead of a hand-run, block-by-block
 * script.
 *
 * **Scope, deliberately**: only forward (postcondition) propagation is precise here.
 * Violation-condition propagation is "trust and forward once per sender" - a block that discovers a
 * violation locally (its own check comes back unsafe) originates one, and a block that receives one
 * just re-broadcasts it upstream without independently re-verifying reachability using its own,
 * possibly more precise, information. CPAchecker's real `packVcond`/`unpackVcond` (plan §3), which
 * needs `cex`/`ω`-style counterexample-path extraction (`ArgTrace`), is not implemented. This is
 * sound (see below), just less precise than a full backward analysis would be.
 *
 * **Accumulating preconditions from more than one predecessor - including a predecessor that is
 * this same block (a cyclic block graph)**: a predecessor that hasn't reported yet contributes
 * [False] to the accumulated precondition, not "no constraint" (top). This is deliberate, and is
 * what makes trusting a violation condition immediately - rather than waiting for every predecessor
 * to have reported - sound: reachability is monotonic in the entry precondition (a weaker/more
 * permissive entry can only reach the same states or more, never fewer, for a fixed abstraction
 * precision), so accumulating this way is exactly an ascending Kleene iteration from bottom toward
 * the true least fixpoint - every intermediate precondition, however incomplete, is a sound subset
 * of the final, fully-converged one, and the fixed, finite global predicate pool guarantees this
 * terminates. A violation found via an under-approximated precondition is therefore already real;
 * it cannot later turn out to be spurious as more information arrives, only more could be found *in
 * addition*.
 *
 * Falling back to *top* instead (as an earlier version of this class did, gated by a "wait until
 * every predecessor has reported" check before trusting anything) is unsound for exactly the case
 * that matters most here: a block that is its own predecessor. Before it has heard from itself even
 * once, `Or(real predecessor value, top)` collapses straight to top - and top is an absorbing
 * element for [Or], so no amount of further, more precise self-reporting can ever recover precision
 * once that happens (confirmed empirically: the paper's own `x`/`y` while-loop example packed a
 * bare `True()` postcondition for its loop-body block under the top-based version, which both
 * stayed that way forever - `Or(anything, True())` is always `True()` - and, combined with "trust
 * and forward", caused a false `UNSAFE` verdict for what is actually a safe program). Falling back
 * to [False] instead has no such absorbing-element problem: `Or(real predecessor value, False())`
 * is just the real predecessor value, unchanged.
 *
 * [checkerFactory] supplies whatever "the ordinary, already-existing analysis" (plan §0/§3)
 * actually is - `xcfa-cli`'s real CEGAR checker in production, or a purpose-built fixture in a
 * test - rather than this class hard-coding a call to `xcfa-cli`'s `getCegarChecker` itself (as an
 * earlier version did, through build-order step 8). That kept this whole module free of a
 * compile-time dependency on `xcfa-cli`, which is what let `xcfa-cli` depend on DSS for
 * `--algorithm DSS` without creating a circular project dependency - `xcfa-dss-analysis` already
 * needed `getCegarChecker` from `xcfa-cli` before this change, so `xcfa-cli` needing DSS back would
 * have closed the cycle. See [runWorkerConfig][hu.bme.mit.theta.xcfa.dss.analysis.runWorkerConfig]
 * for the full rationale.
 */
class PredicateBlockBehavior(
  private val wholeProgram: XCFA,
  private val sourceProcedure: XcfaProcedure,
  private val block: Block,
  private val checkerFactory: (XCFA) -> XcfaChecker,
) : DssBlockBehavior {

  private val knownPreconditions = mutableMapOf<String, Expr<BoolType>>()
  private var lastSentPostcondition: Expr<BoolType>? = null
  private val seenViolationConditionSenders = mutableSetOf<String>()

  /**
   * Null (only for the root) means "no constraint at all" - every other block accumulates one term
   * per predecessor, defaulting to [False] for a predecessor that hasn't reported yet (see the
   * class doc for why that default, not top, is what makes this sound).
   */
  private fun currentPrecondition(): Expr<BoolType>? {
    if (block.predecessorIds.isEmpty()) return null
    return Or(block.predecessorIds.map { knownPreconditions[it] ?: False() })
  }

  private fun recheck(): Collection<DssMessage> {
    val precondition = currentPrecondition()
    val extraction = extractBlockXcfa(sourceProcedure, block, precondition = precondition)
    val result =
      runWorkerConfig(extraction.xcfa, checkerFactory, globalAssumePredicatePrecision(wholeProgram))
    if (result.isUnsafe) {
      return listOf(DssViolationConditionMessage(block.id))
    }
    val postcondition =
      packPostcondition(
        result,
        extraction.locationMapping.getValue(block.violationConditionLocation),
      )
    if (postcondition == False() || postcondition == lastSentPostcondition) {
      return emptyList()
    }
    lastSentPostcondition = postcondition
    return listOf(DssPostConditionMessage(block.id, postcondition))
  }

  override fun initialMessages(): Collection<DssMessage> = recheck()

  override fun onPostCondition(message: DssPostConditionMessage): Collection<DssMessage> {
    val received =
      requireNotNull(message.postcondition) { "Expected a postcondition payload in $message" }
    knownPreconditions[message.senderId] = received
    return recheck()
  }

  override fun onViolationCondition(message: DssViolationConditionMessage): Collection<DssMessage> =
    if (seenViolationConditionSenders.add(message.senderId)) {
      listOf(DssViolationConditionMessage(block.id))
    } else {
      emptyList()
    }
}
