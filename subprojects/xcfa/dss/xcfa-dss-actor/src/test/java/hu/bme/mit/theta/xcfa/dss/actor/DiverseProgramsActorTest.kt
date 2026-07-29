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
import hu.bme.mit.theta.xcfa.dss.analysis.runWorkerConfig
import hu.bme.mit.theta.xcfa.dss.decomposition.Block
import hu.bme.mit.theta.xcfa.dss.decomposition.BlockGraph
import hu.bme.mit.theta.xcfa.dss.decomposition.LinearBlockDecomposition
import hu.bme.mit.theta.xcfa.model.*
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Broadens `PredicateBlockBehaviorTest`/`CyclicBlockGraphAnalysisTest`'s coverage with more
 * structurally diverse example programs run end to end through real per-block CEGAR - a three-way
 * join (more than two predecessors accumulating into one block), a nested branch (a block graph
 * more than one level deep), a loop with a mid-loop break (a two-block cycle, not just a single
 * self-referencing block), and two independent sequential loops (two separate cyclic components in
 * one graph). Every fixture is checked against the non-decomposed baseline, the same way as the
 * existing tests.
 */
class DiverseProgramsActorTest {

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
    executor: (BlockGraph, (Block) -> DssBlockBehavior) -> DssResult = { bg, bf ->
      runDssActors(bg, bf)
    },
  ): DssResult {
    val procedure = wholeProgram.procedures.single()
    val blockGraph = LinearBlockDecomposition().decompose(procedure)
    return assertTimeoutPreemptively<DssResult>(Duration.ofSeconds(30)) {
      executor(blockGraph) { block ->
        PredicateBlockBehavior(
          wholeProgram,
          procedure,
          block,
          checkerFactory = { x -> cegarChecker(x) },
        )
      }
    }
  }

  /**
   * `branch` splits three ways (`y < 0`, `y == 5`, `y > 100`), each arm assigning a distinct value
   * to `x` before rejoining at `join` - three predecessors accumulating into one block, which
   * `PredicateBlockBehaviorTest`'s two-predecessor `joinXcfa` never exercises.
   */
  private fun threeWayJoinXcfa(errorValue: Int?): XCFA =
    xcfa("three-way-join") {
      val main =
        procedure("main") {
          "x" type Int()
          "y" type Int()
          (init to "branch") { havoc("y") }
          ("branch" to "arm1") { assume("(< y 0)") }
          ("branch" to "arm2") { assume("(= y 5)") }
          ("branch" to "arm3") { assume("(> y 100)") }
          ("arm1" to "join") { "x" assign "1" }
          ("arm2" to "join") { "x" assign "2" }
          ("arm3" to "join") { "x" assign if (errorValue != null) "$errorValue" else "3" }
          ("join" to "checkSafe") { assume("(< x 10)") }
          ("join" to "checkErr") { assume("(>= x 10)") }
          ("checkSafe" to final) { nop() }
          ("checkErr" to err) { nop() }
        }
      main.start()
    }

  @Test
  fun `a three-way join reproduces the SAFE verdict when every arm is well-behaved`() {
    val wholeProgram = threeWayJoinXcfa(errorValue = null)
    assertEquals(DssResult.SAFE, checkDecomposed(wholeProgram))
    assert(checkDirectly(wholeProgram).isSafe) { "test fixture itself should be safe" }
  }

  @Test
  fun `a three-way join reproduces the UNSAFE verdict when one arm alone is enough to violate it`() {
    val wholeProgram = threeWayJoinXcfa(errorValue = 15)
    assertEquals(DssResult.UNSAFE, checkDecomposed(wholeProgram))
    assert(checkDirectly(wholeProgram).isUnsafe) { "test fixture itself should be unsafe" }
  }

  /**
   * A branch inside a branch: `outer` splits on `x`, and its `inner` arm splits again before both
   * inner arms rejoin `elseOuter`'s arm at `join` - a block graph more than one hop deep, unlike
   * the single-level branch/join fixtures used elsewhere.
   */
  private fun nestedBranchXcfa(errorValue: Int?): XCFA =
    xcfa("nested-branch") {
      val main =
        procedure("main") {
          "x" type Int()
          "y" type Int()
          (init to "outer") { havoc("x") }
          ("outer" to "inner") { assume("(< x 10)") }
          ("outer" to "elseOuter") { assume("(>= x 10)") }
          ("inner" to "innerA") { assume("(< x 0)") }
          ("inner" to "innerB") { assume("(>= x 0)") }
          ("innerA" to "join") { "y" assign "1" }
          ("innerB" to "join") { "y" assign "2" }
          ("elseOuter" to "join") { "y" assign if (errorValue != null) "$errorValue" else "3" }
          ("join" to "checkSafe") { assume("(< y 10)") }
          ("join" to "checkErr") { assume("(>= y 10)") }
          ("checkSafe" to final) { nop() }
          ("checkErr" to err) { nop() }
        }
      main.start()
    }

  @Test
  fun `a nested branch reproduces the SAFE verdict`() {
    val wholeProgram = nestedBranchXcfa(errorValue = null)
    assertEquals(DssResult.SAFE, checkDecomposed(wholeProgram))
    assert(checkDirectly(wholeProgram).isSafe) { "test fixture itself should be safe" }
  }

  @Test
  fun `a nested branch reproduces the UNSAFE verdict when the outer else-arm alone violates it`() {
    val wholeProgram = nestedBranchXcfa(errorValue = 15)
    assertEquals(DssResult.UNSAFE, checkDecomposed(wholeProgram))
    assert(checkDirectly(wholeProgram).isUnsafe) { "test fixture itself should be unsafe" }
  }

  /**
   * A loop whose body itself branches, either breaking out early (`x >= 5`) or looping back (`x <
   * 5`) - under `blk_linear` this decomposes into a genuine *two*-block cycle (loop-head-side block
   * <-> break-check-side block), not the single self-referencing block the paper's own `x`/`y`
   * example produces (see `CyclicBlockGraphAnalysisTest`). Every path into `afterloop` (direct exit
   * at `x >= 10`, or the break at `x >= 5`) guarantees `x >= 5`.
   */
  private fun loopWithBreakXcfa(safe: Boolean): XCFA =
    xcfa("loop-with-break") {
      val main =
        procedure("main") {
          "x" type Int()
          (init to "loophead") { "x" assign "0" }
          ("loophead" to "body") { assume("(< x 10)") }
          ("loophead" to "afterloop") { assume("(>= x 10)") }
          ("body" to "checkBreak") { "x" assign "(+ x 1)" }
          ("checkBreak" to "afterloop") { assume("(>= x 5)") }
          ("checkBreak" to "loophead") { assume("(< x 5)") }
          if (safe) {
            ("afterloop" to final) { assume("(< x 100)") }
            ("afterloop" to err) { assume("(>= x 100)") }
          } else {
            ("afterloop" to final) { assume("(< x 5)") }
            ("afterloop" to err) { assume("(>= x 5)") }
          }
        }
      main.start()
    }

  @Test
  fun `a loop with a mid-loop break reproduces the SAFE verdict through a two-block cycle`() {
    val wholeProgram = loopWithBreakXcfa(safe = true)
    assertEquals(DssResult.SAFE, checkDecomposed(wholeProgram))
    assert(checkDirectly(wholeProgram).isSafe) { "test fixture itself should be safe" }
  }

  @Test
  fun `a loop with a mid-loop break reproduces the UNSAFE verdict through a two-block cycle`() {
    val wholeProgram = loopWithBreakXcfa(safe = false)
    assertEquals(DssResult.UNSAFE, checkDecomposed(wholeProgram))
    assert(checkDirectly(wholeProgram).isUnsafe) { "test fixture itself should be unsafe" }
  }

  /**
   * Two independent loops in sequence, each its own self-referencing block, connected by a plain
   * straight-line block ("mid") - two separate cyclic components in one block graph, rather than
   * one loop or one loop nested inside another. `x`, fixed by the first loop, has to survive
   * correctly all the way through the second loop's own accumulation (which never touches `x` at
   * all) for the final check to be decided correctly.
   *
   * The final check deliberately reuses loop 1's own guard predicate (`x >= 5`/`x < 5`) rather than
   * asking a fresh question like "is `x` exactly `5`": Cartesian predicate abstraction
   * (`Domain.PRED_CART`,
   * [`WorkerConfigDelegator`](#workerconfigdelegator-the-thin-orchestration-layer-literally)'s
   * default) tracks each predicate's truth value independently and cannot always correlate a
   * predicate against exactly how many times a loop has iterated unless that predicate is already
   * the loop's own guard - checking `x == 5` here (provably true, since `x` only ever exits loop 1
   * at exactly `5`) made a single block's own postcondition collapse to an unconstrained `True()`
   * even for a single, self-contained loop with no second loop involved at all. Not a DSS-layer bug
   * - a precision limitation of the underlying single-block CEGAR check itself - so the fixture
   *   sidesteps it rather than working around Theta's abstraction domain.
   */
  private fun twoSequentialLoopsXcfa(safe: Boolean): XCFA =
    xcfa("two-sequential-loops") {
      val main =
        procedure("main") {
          "x" type Int()
          "y" type Int()
          (init to "loop1head") { "x" assign "0" }
          ("loop1head" to "loop1body") { assume("(< x 5)") }
          ("loop1head" to "mid") { assume("(>= x 5)") }
          ("loop1body" to "loop1head") { "x" assign "(+ x 1)" }
          ("mid" to "loop2head") { "y" assign "0" }
          ("loop2head" to "loop2body") { assume("(< y 5)") }
          ("loop2head" to "afterloop2") { assume("(>= y 5)") }
          ("loop2body" to "loop2head") { "y" assign "(+ y 1)" }
          if (safe) {
            ("afterloop2" to final) { assume("(>= x 5)") }
            ("afterloop2" to err) { assume("(< x 5)") }
          } else {
            ("afterloop2" to final) { assume("(< x 5)") }
            ("afterloop2" to err) { assume("(>= x 5)") }
          }
        }
      main.start()
    }

  @Test
  fun `two independent sequential loops reproduce the SAFE verdict`() {
    val wholeProgram = twoSequentialLoopsXcfa(safe = true)
    assertEquals(DssResult.SAFE, checkDecomposed(wholeProgram))
    assert(checkDirectly(wholeProgram).isSafe) { "test fixture itself should be safe" }
  }

  @Test
  fun `two independent sequential loops reproduce the UNSAFE verdict`() {
    val wholeProgram = twoSequentialLoopsXcfa(safe = false)
    assertEquals(DssResult.UNSAFE, checkDecomposed(wholeProgram))
    assert(checkDirectly(wholeProgram).isUnsafe) { "test fixture itself should be unsafe" }
  }

  @Test
  fun `the sequential executor agrees with the concurrent one on both cyclic fixtures`() {
    val sequentially: (BlockGraph, (Block) -> DssBlockBehavior) -> DssResult = { bg, bf ->
      runDssActorsSequentially(bg, bf)
    }

    assertEquals(DssResult.SAFE, checkDecomposed(loopWithBreakXcfa(safe = true), sequentially))
    assertEquals(DssResult.UNSAFE, checkDecomposed(loopWithBreakXcfa(safe = false), sequentially))
    assertEquals(DssResult.SAFE, checkDecomposed(twoSequentialLoopsXcfa(safe = true), sequentially))
    assertEquals(
      DssResult.UNSAFE,
      checkDecomposed(twoSequentialLoopsXcfa(safe = false), sequentially),
    )
  }
}
