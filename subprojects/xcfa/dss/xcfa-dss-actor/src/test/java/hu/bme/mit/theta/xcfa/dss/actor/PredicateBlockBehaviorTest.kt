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

import hu.bme.mit.theta.common.logging.NullLogger
import hu.bme.mit.theta.core.type.inttype.IntExprs.*
import hu.bme.mit.theta.frontend.ParseContext
import hu.bme.mit.theta.xcfa.cli.checkers.getCegarChecker
import hu.bme.mit.theta.xcfa.cli.params.defaultPredicateCegarConfig
import hu.bme.mit.theta.xcfa.cli.utils.ensureDefaultSolversRegistered
import hu.bme.mit.theta.xcfa.dss.analysis.CheckerFactory
import hu.bme.mit.theta.xcfa.dss.analysis.DssCheckerRoster
import hu.bme.mit.theta.xcfa.dss.analysis.runWorkerConfig
import hu.bme.mit.theta.xcfa.dss.decomposition.LinearBlockDecomposition
import hu.bme.mit.theta.xcfa.model.*
import java.time.Duration
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Build-order step 5's actual correctness checkpoint: a real, multi-block, `LINEAR_DECOMPOSITION`
 * program checked through the full actor runtime (routing, quiescence, [PredicateBlockBehavior]
 * driving real per-block CEGAR) reproduces the same verdict as checking the un-decomposed program
 * directly.
 */
class PredicateBlockBehaviorTest {

  companion object {

    @JvmStatic
    @BeforeAll
    fun registerSolvers() {
      ensureDefaultSolversRegistered(defaultPredicateCegarConfig(), NullLogger.getInstance())
    }
  }

  private fun cegarChecker(xcfa: XCFA) =
    getCegarChecker(
      xcfa,
      emptySet(),
      ParseContext(),
      defaultPredicateCegarConfig(),
      NullLogger.getInstance(),
    )

  private fun checkDirectly(xcfa: XCFA) =
    runWorkerConfig(xcfa, checkerFactory = { x -> cegarChecker(x) })

  private fun checkDecomposed(
    wholeProgram: XCFA,
    useGlobalPredicatePool: Boolean = true,
  ): DssResult {
    val procedure = wholeProgram.procedures.single()
    val blockGraph = LinearBlockDecomposition().decompose(procedure)
    // One roster, built here and shared by every block via behaviorFor below - not one per block -
    // per DssCheckerRoster's own contract (a fresh-per-block roster would still trivially "balance"
    // its own single checker against itself, so this distinction wouldn't even show up as a bug in
    // a
    // single-checker test like this one; see `checkerRoster distributes...` below for a test that
    // would actually catch getting this wrong).
    val checkerRoster = DssCheckerRoster(listOf { x -> cegarChecker(x) })
    return assertTimeoutPreemptively<DssResult>(Duration.ofSeconds(30)) {
      runDssActors(
        blockGraph,
        behaviorFor = { block ->
          PredicateBlockBehavior(
            wholeProgram,
            procedure,
            block,
            checkerRoster,
            useGlobalPredicatePool,
          )
        },
      )
    }
  }

  /** init->branch assigns x, then branches on x < 10 vs x >= 10 into a safe/error location. */
  private fun branchingXcfa(assignedValue: String): XCFA =
    xcfa("predicate-block-behavior") {
      val main =
        procedure("main") {
          "x" type Int()
          (init to "branch") { "x" assign assignedValue }
          ("branch" to "checkA") { assume("(< x 10)") }
          ("branch" to "checkB") { assume("(>= x 10)") }
          ("checkA" to final) { nop() }
          ("checkB" to err) { nop() }
        }
      main.start()
    }

  @Test
  fun `a decomposed SAFE program reproduces the non-decomposed SAFE verdict`() {
    val wholeProgram = branchingXcfa(assignedValue = "5")
    assertEquals(DssResult.SAFE, checkDecomposed(wholeProgram))
    assert(checkDirectly(wholeProgram).isSafe) { "test fixture itself should be safe" }
  }

  @Test
  fun `a decomposed UNSAFE program reproduces the non-decomposed UNSAFE verdict`() {
    val wholeProgram = branchingXcfa(assignedValue = "20")
    assertEquals(DssResult.UNSAFE, checkDecomposed(wholeProgram))
    assert(checkDirectly(wholeProgram).isUnsafe) { "test fixture itself should be unsafe" }
  }

  /**
   * Two branches join back together before the safety check, so the join block has two predecessors
   * and must accumulate both of their postconditions before it can precisely tell whether the join
   * point's own successors (`checkD`/`err` here) are reachable - one path alone (x == 3) would say
   * no, but the other (x == 15) does reach it.
   */
  private fun joinXcfa(): XCFA =
    xcfa("predicate-block-behavior-join") {
      val main =
        procedure("main") {
          "x" type Int()
          "y" type Int()
          (init to "branch") { havoc("y") }
          ("branch" to "pathA") { assume("(< y 0)") }
          ("branch" to "pathB") { assume("(>= y 0)") }
          ("pathA" to "join") { "x" assign "3" }
          ("pathB" to "join") { "x" assign "15" }
          ("join" to "checkC") { assume("(< x 10)") }
          ("join" to "checkD") { assume("(>= x 10)") }
          ("checkC" to final) { nop() }
          ("checkD" to err) { nop() }
        }
      main.start()
    }

  @Test
  fun `a join block accumulates both predecessors before reporting the correct UNSAFE verdict`() {
    val wholeProgram = joinXcfa()
    assertEquals(DssResult.UNSAFE, checkDecomposed(wholeProgram))
    assert(checkDirectly(wholeProgram).isUnsafe) { "test fixture itself should be unsafe" }
  }

  /**
   * [DssCheckerRoster]'s own default (round-robin) proven against a real, multi-recheck run, not
   * just the pure unit tests in `DssCheckerRosterTest` - the join fixture's own doc explains why
   * its join block rechecks more than once (it has two predecessors, each arriving separately), so
   * a single-checker roster couldn't tell this test apart from the join test above. Both checkers
   * below are the *same* config (only which counter they bump differs) - proving
   * distribution/selection, not that differing checkers still converge to the same verdict (see
   * [DssCheckerRoster]'s own doc for why mixing genuinely different strategies is a separate,
   * unproven concern this test doesn't cover).
   */
  @Test
  fun `the default round-robin roster distributes real checks across every checker it's given`() {
    val wholeProgram = joinXcfa()
    val procedure = wholeProgram.procedures.single()
    val blockGraph = LinearBlockDecomposition().decompose(procedure)
    val counts = AtomicIntegerArray(2)
    val checkers: List<CheckerFactory> =
      listOf(
        { x: XCFA ->
          counts.incrementAndGet(0)
          cegarChecker(x)
        },
        { x: XCFA ->
          counts.incrementAndGet(1)
          cegarChecker(x)
        },
      )
    val checkerRoster = DssCheckerRoster(checkers)
    val result =
      assertTimeoutPreemptively<DssResult>(Duration.ofSeconds(30)) {
        runDssActors(
          blockGraph,
          behaviorFor = { block ->
            PredicateBlockBehavior(wholeProgram, procedure, block, checkerRoster)
          },
        )
      }
    assertEquals(DssResult.UNSAFE, result)
    val (countA, countB) = counts.get(0) to counts.get(1)
    assert(countA > 0 && countB > 0) {
      "expected both checkers in the roster to be used at least once, got ($countA, $countB)"
    }
    assert(abs(countA - countB) <= 1) {
      "expected round-robin usage to differ by at most 1, got ($countA, $countB)"
    }
  }

  /**
   * Demonstrates what [PredicateBlockBehavior]'s own `useGlobalPredicatePool` doc promises: turning
   * the pool off reproduces the exact precision gap [globalAssumePredicatePrecision] exists to
   * close, for the same reason its own class doc gives. `B_root` (`init->branch`, `x := 5`) has no
   * violation location of its own to refute against, so without the pool it never refines past an
   * empty precision and packs `True()` at `branch` regardless of what `x` actually is. `B_checkB`
   * (`branch->checkB->err`, `assume(x >= 10)`) then receives that unconstrained `True()` as its
   * only precondition - its own extracted mini-program never even sees the `x := 5` assignment,
   * which lives in `B_root`'s block, not its own - so `x` is free there and `assume(x >= 10)` is
   * trivially satisfiable: a false positive. Still sound (a spurious UNSAFE is a real, if
   * unhelpfully broad, consequence of `True()` being a valid over-approximation - never a missed
   * real violation), just exactly the imprecision the pool is meant to prevent.
   */
  @Test
  fun `turning the global predicate pool off reproduces its own known precision gap`() {
    val wholeProgram = branchingXcfa(assignedValue = "5")
    assertEquals(DssResult.SAFE, checkDecomposed(wholeProgram, useGlobalPredicatePool = true))
    assertEquals(DssResult.UNSAFE, checkDecomposed(wholeProgram, useGlobalPredicatePool = false))
    assert(checkDirectly(wholeProgram).isSafe) { "test fixture itself should be safe" }
  }
}
