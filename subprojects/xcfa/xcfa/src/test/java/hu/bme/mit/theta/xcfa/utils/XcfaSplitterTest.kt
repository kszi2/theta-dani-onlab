/*
 *  Copyright 2025 Budapest University of Technology and Economics
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
package hu.bme.mit.theta.xcfa.utils

import hu.bme.mit.theta.xcfa.model.*
import java.util.Optional
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for [XCFA.splitByCutset].
 *
 * All XCFAs are built by direct construction (bypassing the DSL) so that the tests hold
 * references to the exact edge objects used in the cutsets.
 */
class XcfaSplitterTest {

  // ---------------------------------------------------------------------------
  // Helpers (mirrors the pattern in XcfaCutsetsTest)
  // ---------------------------------------------------------------------------

  private fun loc(
    name: String,
    init: Boolean = false,
    error: Boolean = false,
    final: Boolean = false,
  ) = XcfaLocation(name, initial = init, error = error, final = final, metadata = EmptyMetaData)

  private fun edge(from: XcfaLocation, to: XcfaLocation, label: XcfaLabel = NopLabel): XcfaEdge {
    val e = XcfaEdge(from, to, label, EmptyMetaData)
    from.outgoingEdges.add(e)
    to.incomingEdges.add(e)
    return e
  }

  private fun proc(
    name: String,
    init: XcfaLocation,
    error: Optional<XcfaLocation>,
    locs: Set<XcfaLocation>,
    edges: Set<XcfaEdge>,
    final: Optional<XcfaLocation> = Optional.empty(),
  ) = XcfaProcedure(name, emptyList(), emptySet(), locs, edges, init, final, error)

  /** Wraps a single [XcfaProcedure] in a minimal XCFA so [splitByCutset] can be called on it. */
  private fun xcfaOf(p: XcfaProcedure): XCFA {
    val xcfa = XCFA("model", emptySet())
    p.parent = xcfa
    xcfa.recreate(setOf(p), listOf(Pair(p, emptyList())))
    return xcfa
  }

  // ---------------------------------------------------------------------------
  // Linear path: init --e1--> A --e2--> error
  // ---------------------------------------------------------------------------

  private fun linearXcfa(): Triple<XCFA, XcfaEdge, XcfaEdge> {
    val init = loc("init", init = true)
    val a = loc("A")
    val error = loc("error", error = true)
    val e1 = edge(init, a)
    val e2 = edge(a, error)
    val p = proc("main", init, Optional.of(error), setOf(init, a, error), setOf(e1, e2))
    return Triple(xcfaOf(p), e1, e2)
  }

  @Test
  fun `split linear path at first edge - before has init and cut_final`() {
    val (xcfa, e1, _) = linearXcfa()
    val cutset = CutsetWithPartitionSizes(setOf(e1), before = 1, after = 2)

    val (before, _) = xcfa.splitByCutset(cutset)
    val beforeProc = before.initProcedures.single().first

    // Locations: init-copy + cut_final
    assertEquals(2, beforeProc.locs.size)
    assertTrue(beforeProc.locs.any { it.initial })
    assertTrue(beforeProc.locs.any { it.final })
    assertFalse(beforeProc.locs.any { it.error })
  }

  @Test
  fun `split linear path at first edge - before edge keeps original label`() {
    val (xcfa, e1, _) = linearXcfa()
    val cutset = CutsetWithPartitionSizes(setOf(e1), before = 1, after = 2)

    val (before, _) = xcfa.splitByCutset(cutset)
    val beforeProc = before.initProcedures.single().first

    // The single edge in before should carry e1's original label (NopLabel in this fixture).
    assertEquals(1, beforeProc.edges.size)
    assertEquals(e1.label, beforeProc.edges.single().label)
  }

  @Test
  fun `split linear path at first edge - after has cut_init A and error`() {
    val (xcfa, e1, e2) = linearXcfa()
    val cutset = CutsetWithPartitionSizes(setOf(e1), before = 1, after = 2)

    val (_, after) = xcfa.splitByCutset(cutset)
    val afterProc = after.initProcedures.single().first

    // Locations: cut_init + A-copy + error-copy
    assertEquals(3, afterProc.locs.size)
    assertTrue(afterProc.locs.any { it.initial })
    assertTrue(afterProc.locs.any { it.error })
    assertFalse(afterProc.locs.any { it.final })
  }

  @Test
  fun `split linear path at first edge - after has NopLabel entry then original e2 label`() {
    val (xcfa, e1, e2) = linearXcfa()
    val cutset = CutsetWithPartitionSizes(setOf(e1), before = 1, after = 2)

    val (_, after) = xcfa.splitByCutset(cutset)
    val afterProc = after.initProcedures.single().first

    assertEquals(2, afterProc.edges.size)

    val entryEdge = afterProc.edges.single { it.source.initial }
    assertEquals(NopLabel, entryEdge.label)

    val innerEdge = afterProc.edges.single { !it.source.initial }
    assertEquals(e2.label, innerEdge.label)
  }

  @Test
  fun `split linear path at second edge - before has init A and cut_final`() {
    val (xcfa, _, e2) = linearXcfa()
    val cutset = CutsetWithPartitionSizes(setOf(e2), before = 2, after = 1)

    val (before, _) = xcfa.splitByCutset(cutset)
    val beforeProc = before.initProcedures.single().first

    // Locations: init-copy + A-copy + cut_final
    assertEquals(3, beforeProc.locs.size)
    assertTrue(beforeProc.locs.any { it.initial })
    assertTrue(beforeProc.locs.any { it.final })
    assertFalse(beforeProc.locs.any { it.error })
  }

  @Test
  fun `split linear path at second edge - before edge to cut_final keeps e2 label`() {
    val (xcfa, e1, e2) = linearXcfa()
    val cutset = CutsetWithPartitionSizes(setOf(e2), before = 2, after = 1)

    val (before, _) = xcfa.splitByCutset(cutset)
    val beforeProc = before.initProcedures.single().first

    // Two edges: e1 (init→A) and the replacement cut edge (A→cut_final with e2's label).
    assertEquals(2, beforeProc.edges.size)
    val cutEdge = beforeProc.edges.single { it.target.final }
    assertEquals(e2.label, cutEdge.label)
  }

  @Test
  fun `split linear path at second edge - after has only cut_init and error`() {
    val (xcfa, _, e2) = linearXcfa()
    val cutset = CutsetWithPartitionSizes(setOf(e2), before = 2, after = 1)

    val (_, after) = xcfa.splitByCutset(cutset)
    val afterProc = after.initProcedures.single().first

    assertEquals(2, afterProc.locs.size)
    assertTrue(afterProc.locs.any { it.initial })
    assertTrue(afterProc.locs.any { it.error })

    // Single entry edge: cut_init → error with NopLabel
    assertEquals(1, afterProc.edges.size)
    assertEquals(NopLabel, afterProc.edges.single().label)
  }

  // ---------------------------------------------------------------------------
  // Original XCFA is not mutated
  // ---------------------------------------------------------------------------

  @Test
  fun `splitByCutset does not mutate original XCFA locations`() {
    val (xcfa, e1, _) = linearXcfa()
    val origProc = xcfa.initProcedures.single().first
    val initOutBefore = origProc.initLoc.outgoingEdges.toSet()

    val cutset = CutsetWithPartitionSizes(setOf(e1), before = 1, after = 2)
    xcfa.splitByCutset(cutset)

    assertEquals(initOutBefore, origProc.initLoc.outgoingEdges)
  }

  // ---------------------------------------------------------------------------
  // Parallel paths:
  //   init --e1--> A --e3--> error
  //   init --e2--> B --e4--> error
  //
  //   Cut at {e1, e2}  (before={init}, after={A,B,error})
  // ---------------------------------------------------------------------------

  @Test
  fun `split parallel paths at balanced cutset`() {
    val init = loc("init", init = true)
    val a = loc("A")
    val b = loc("B")
    val error = loc("error", error = true)
    val e1 = edge(init, a)
    val e2 = edge(init, b)
    val e3 = edge(a, error)
    val e4 = edge(b, error)
    val p = proc("main", init, Optional.of(error), setOf(init, a, b, error), setOf(e1, e2, e3, e4))
    val xcfa = xcfaOf(p)

    val cutset = CutsetWithPartitionSizes(setOf(e1, e2), before = 1, after = 3)
    val (before, after) = xcfa.splitByCutset(cutset)

    val beforeProc = before.initProcedures.single().first
    val afterProc = after.initProcedures.single().first

    // Before: init-copy + cut_final
    // Both cutset edges go from init (NopLabel, EmptyMetaData) → cut_final, so the set
    // deduplicates them to a single edge.
    assertEquals(2, beforeProc.locs.size)
    assertEquals(1, beforeProc.edges.size)
    assertTrue(beforeProc.edges.all { it.target.final })
    assertTrue(beforeProc.edges.all { it.label == NopLabel }) // e1 and e2 were NopLabel

    // After: cut_init + A + B + error; two NopLabel entry edges + two inner edges
    assertEquals(4, afterProc.locs.size)
    assertEquals(4, afterProc.edges.size)
    assertEquals(2, afterProc.edges.count { it.source.initial })     // entry edges from cut_init
    assertEquals(2, afterProc.edges.count { !it.source.initial })    // inner edges A→error, B→error
  }

  // ---------------------------------------------------------------------------
  // initLoc / finalLoc / errorLoc pointers are set correctly
  // ---------------------------------------------------------------------------

  @Test
  fun `before XCFA initLoc is initial and finalLoc is cut_final`() {
    val (xcfa, e1, _) = linearXcfa()
    val cutset = CutsetWithPartitionSizes(setOf(e1), before = 1, after = 2)

    val (before, _) = xcfa.splitByCutset(cutset)
    val beforeProc = before.initProcedures.single().first

    assertTrue(beforeProc.initLoc.initial)
    assertTrue(beforeProc.finalLoc.isPresent)
    assertTrue(beforeProc.finalLoc.get().final)
    assertFalse(beforeProc.errorLoc.isPresent)
  }

  @Test
  fun `after XCFA initLoc is cut_init and errorLoc is present`() {
    val (xcfa, e1, _) = linearXcfa()
    val cutset = CutsetWithPartitionSizes(setOf(e1), before = 1, after = 2)

    val (_, after) = xcfa.splitByCutset(cutset)
    val afterProc = after.initProcedures.single().first

    assertTrue(afterProc.initLoc.initial)
    assertTrue(afterProc.errorLoc.isPresent)
    assertTrue(afterProc.errorLoc.get().error)
  }

  // ---------------------------------------------------------------------------
  // Error reachability in the after-XCFA
  // ---------------------------------------------------------------------------

  @Test
  fun `error is reachable from cut_init in after XCFA`() {
    val (xcfa, e1, _) = linearXcfa()
    val cutset = CutsetWithPartitionSizes(setOf(e1), before = 1, after = 2)

    val (_, after) = xcfa.splitByCutset(cutset)
    val afterProc = after.initProcedures.single().first
    val errorLoc = afterProc.errorLoc.get()

    // Verify the after-XCFA returns a non-empty cutset (error IS reachable from init).
    assertTrue(afterProc.getMinimalEdgeCutsets(afterProc.initLoc, errorLoc).isNotEmpty())
  }
}
