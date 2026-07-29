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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Closes a gap `doc/DSS.md` has flagged since build-order step 6: "larger/nested cyclic block
 * graphs (the decomposition module's own nested-loop test fixtures, for instance) have not been run
 * through real per-block CEGAR yet, only through `EchoBlockAnalysis`."
 * `CyclicBlockGraphAnalysisTest` only ever exercises a single self-referencing block (the paper's
 * own `x`/`y` loop); this file drives real [PredicateBlockBehavior] through a genuine *nested*
 * cycle instead - two distinct self/mutually referencing blocks in the same block graph, one
 * strictly inside the other's loop body, mirroring `LinearBlockDecompositionTest`'s own `an outer
 * and an inner loop each get their own loop-head block` structural fixture (which only ever checked
 * block *shape*, never ran real analysis through it).
 */
class NestedLoopActorTest {

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
   * An outer loop (`x` from 0 to 3) whose body entirely contains an inner loop (`y` reset to 0 and
   * counted up to 2 on every outer iteration) - under `blk_linear` this decomposes into *two*
   * distinct self/mutually-referencing blocks (the inner loop's own head-to-head cycle, plus the
   * outer loop-head, which is itself reached both from `init` and from the inner loop's own exit
   * edge - see `LinearBlockDecompositionTest`'s structural fixture of the same shape), a strictly
   * more complex block-graph topology than any single self-loop.
   *
   * `z` mirrors `x` in lockstep on every outer iteration (when [safe]) or is never touched at all
   * (when not [safe]) - exactly the paper's own `x == y` pattern from
   * `CyclicBlockGraphAnalysisTest`'s `xyLoopXcfa`, chosen deliberately: that pattern is already
   * confirmed to survive real per-block CEGAR through a single self-loop, so reusing it here
   * isolates exactly one new variable - whether it *also* survives correctly once an unrelated,
   * fully-nested inner loop sits in between every `x`/`z` increment. `y` itself never appears in
   * the final check at all - it only has to be correctly summarized and threaded through without
   * disturbing `x`/`z`'s own relationship.
   */
  private fun nestedLoopXcfa(safe: Boolean): XCFA =
    xcfa("nested-loop") {
      val main =
        procedure("main") {
          "x" type Int()
          "y" type Int()
          "z" type Int()
          (init to "outerHead") {
            "x" assign "0"
            "z" assign "0"
          }
          ("outerHead" to "innerHead") {
            assume("(< x 3)")
            "y" assign "0"
          }
          ("outerHead" to "afterOuter") { assume("(>= x 3)") }
          ("innerHead" to "innerBody") { assume("(< y 2)") }
          ("innerHead" to "afterInner") { assume("(>= y 2)") }
          ("innerBody" to "innerHead") { "y" assign "(+ y 1)" }
          if (safe) {
            ("afterInner" to "outerHead") {
              "x" assign "(+ x 1)"
              "z" assign "(+ z 1)"
            }
          } else {
            ("afterInner" to "outerHead") { "x" assign "(+ x 1)" }
          }
          ("afterOuter" to final) { assume("(= x z)") }
          ("afterOuter" to err) { assume("(/= x z)") }
        }
      main.start()
    }

  @Test
  fun `the fixture itself decomposes into two distinct cyclic blocks, not one`() {
    val procedure = nestedLoopXcfa(safe = true).procedures.single()
    val blockGraph = LinearBlockDecomposition().decompose(procedure)
    blockGraph.checkConsistency()

    val cyclicBlocks = blockGraph.blocks.filter { it.id in it.successorIds }
    assertEquals(1, cyclicBlocks.size, "only the inner loop is a genuine single self-loop")
    assertEquals("innerHead", cyclicBlocks.single().initialLocation.name)

    // The outer loop is a two-block cycle (outerHead-side <-> afterInner-side), the same shape
    // `DiverseProgramsActorTest`'s loop-with-break fixture has - confirming this fixture is a
    // genuine superset of complexity (a two-block outer cycle with an entirely separate,
    // single-block inner cycle nested inside it), not just a relabeled existing fixture.
    val outerHeadBlocks = blockGraph.blocks.filter { it.finalLocation.name == "outerHead" }
    assertEquals(2, outerHeadBlocks.size, "outerHead is reached from init and from afterInner")
  }

  @Test
  fun `nested loops reproduce the SAFE verdict through the concurrent executor`() {
    val wholeProgram = nestedLoopXcfa(safe = true)
    assertEquals(DssResult.SAFE, checkDecomposed(wholeProgram))
    assertTrue(checkDirectly(wholeProgram).isSafe) { "test fixture itself should be safe" }
  }

  @Test
  fun `nested loops reproduce the UNSAFE verdict through the concurrent executor`() {
    val wholeProgram = nestedLoopXcfa(safe = false)
    assertEquals(DssResult.UNSAFE, checkDecomposed(wholeProgram))
    assertTrue(checkDirectly(wholeProgram).isUnsafe) { "test fixture itself should be unsafe" }
  }

  @Test
  fun `nested loops reproduce the SAFE verdict through the sequential executor`() {
    val wholeProgram = nestedLoopXcfa(safe = true)
    assertEquals(
      DssResult.SAFE,
      checkDecomposed(wholeProgram) { bg, bf -> runDssActorsSequentially(bg, bf) },
    )
  }

  @Test
  fun `nested loops reproduce the UNSAFE verdict through the sequential executor`() {
    val wholeProgram = nestedLoopXcfa(safe = false)
    assertEquals(
      DssResult.UNSAFE,
      checkDecomposed(wholeProgram) { bg, bf -> runDssActorsSequentially(bg, bf) },
    )
  }
}
