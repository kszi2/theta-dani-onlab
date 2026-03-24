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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XcfaCutsetsTest {

  // -------------------------------------------------------------------------
  // Helpers for direct graph construction (bypasses the XCFA builder DSL so
  // tests can hold references to the exact edge objects they expect).
  // -------------------------------------------------------------------------

  private fun loc(
    name: String,
    init: Boolean = false,
    error: Boolean = false,
    final: Boolean = false,
  ) = XcfaLocation(name, initial = init, error = error, final = final, metadata = EmptyMetaData)

  /** Creates an edge and wires it into both endpoint adjacency lists. */
  private fun edge(from: XcfaLocation, to: XcfaLocation): XcfaEdge {
    val e = XcfaEdge(from, to, NopLabel, EmptyMetaData)
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
  ) = XcfaProcedure(name, emptyList(), emptySet(), locs, edges, init, Optional.empty(), error)

  // -------------------------------------------------------------------------
  // XcfaProcedure.getMinimalEdgeCutsets tests
  // -------------------------------------------------------------------------

  @Test
  fun `no path to error returns empty set`() {
    val init = loc("init", init = true)
    val mid = loc("mid")
    val error = loc("error", error = true)
    // init → mid, but error is unreachable
    val e1 = edge(init, mid)
    val p = proc("p", init, Optional.of(error), setOf(init, mid, error), setOf(e1))

    assertTrue(p.getMinimalEdgeCutsets(init, error).isEmpty())
  }

  @Test
  fun `direct single edge to error`() {
    val init = loc("init", init = true)
    val error = loc("error", error = true)
    val e1 = edge(init, error)
    val p = proc("p", init, Optional.of(error), setOf(init, error), setOf(e1))

    assertEquals(setOf(setOf(e1)), p.getMinimalEdgeCutsets(init, error))
  }

  @Test
  fun `linear path init to mid to error`() {
    // init --e1--> mid --e2--> error
    // Minimal cutsets: {e1} and {e2}
    val init = loc("init", init = true)
    val mid = loc("mid")
    val error = loc("error", error = true)
    val e1 = edge(init, mid)
    val e2 = edge(mid, error)
    val p = proc("p", init, Optional.of(error), setOf(init, mid, error), setOf(e1, e2))

    assertEquals(setOf(setOf(e1), setOf(e2)), p.getMinimalEdgeCutsets(init, error))
  }

  @Test
  fun `two parallel paths produce four pairwise cutsets`() {
    // init --e1--> A --e3--> error
    // init --e2--> B --e4--> error
    // Paths: {e1,e3} and {e2,e4}
    // Minimal cutsets: {e1,e2}, {e1,e4}, {e3,e2}, {e3,e4}
    val init = loc("init", init = true)
    val a = loc("A")
    val b = loc("B")
    val error = loc("error", error = true)
    val e1 = edge(init, a)
    val e2 = edge(init, b)
    val e3 = edge(a, error)
    val e4 = edge(b, error)
    val p = proc("p", init, Optional.of(error), setOf(init, a, b, error), setOf(e1, e2, e3, e4))

    assertEquals(
      setOf(setOf(e1, e2), setOf(e1, e4), setOf(e3, e2), setOf(e3, e4)),
      p.getMinimalEdgeCutsets(init, error),
    )
  }

  @Test
  fun `direct edge plus two-hop path share the direct edge in every cutset`() {
    // init --e1--> error          (direct, path P1 = {e1})
    // init --e2--> mid --e3--> error  (two-hop, path P2 = {e2,e3})
    // e1 must appear in every cutset (P1 can only be cut by e1).
    // Minimal cutsets: {e1,e2} and {e1,e3}
    val init = loc("init", init = true)
    val mid = loc("mid")
    val error = loc("error", error = true)
    val e1 = edge(init, error)
    val e2 = edge(init, mid)
    val e3 = edge(mid, error)
    val p = proc("p", init, Optional.of(error), setOf(init, mid, error), setOf(e1, e2, e3))

    assertEquals(setOf(setOf(e1, e2), setOf(e1, e3)), p.getMinimalEdgeCutsets(init, error))
  }

  @Test
  fun `graph with cycle still finds correct cutsets`() {
    // init --e1--> A --e2--> error
    //              ^         |
    //              |--e3-----|  (back edge from error... but error is the sink so DFS stops)
    // Actually: init --e1--> A --e2--> error
    //                A <--e3-- B
    //           init --e4--> B --e5--> error
    // Paths: [e1,e2], [e4,e5], [e4,e3,e2]   (A→B is not present, B→A via e3 is)
    val init = loc("init", init = true)
    val a = loc("A")
    val b = loc("B")
    val error = loc("error", error = true)
    val e1 = edge(init, a) // init → A
    val e2 = edge(a, error) // A → error
    val e3 = edge(b, a) // B → A  (creates another path through B)
    val e4 = edge(init, b) // init → B
    val e5 = edge(b, error) // B → error
    val p = proc("p", init, Optional.of(error), setOf(init, a, b, error), setOf(e1, e2, e3, e4, e5))

    // Paths from init to error:
    //   P1: e1, e2         (init→A→error)
    //   P2: e4, e5         (init→B→error)
    //   P3: e4, e3, e2     (init→B→A→error)
    // Minimal cutsets must hit all three paths.
    val cutsets = p.getMinimalEdgeCutsets(init, error)

    // Every returned set must actually be a cutset (hit every path).
    val allPaths = listOf(setOf(e1, e2), setOf(e4, e5), setOf(e4, e3, e2))
    for (cs in cutsets) {
      assertTrue(
        allPaths.all { path -> cs.any { it in path } },
        "Cutset $cs does not cut all paths",
      )
    }
    // No returned set should be dominated by another.
    for (cs in cutsets) {
      assertTrue(
        cutsets.none { other -> other !== cs && other.isSubsetOf(cs) },
        "Cutset $cs is not minimal",
      )
    }
  }

  // -------------------------------------------------------------------------
  // XCFA.getMinimalCutsets integration test (uses the DSL builder)
  // -------------------------------------------------------------------------

  @Test
  fun `xcfa getMinimalCutsets returns cutsets for init procedure`() {
    lateinit var directEdge: XcfaEdge
    val model = xcfa("test") { procedure("main") { directEdge = (init to err) { skip() } }.start() }

    val cutsets = model.getMinimalCutsets()
    assertEquals(1, cutsets.size)
    assertEquals(setOf(directEdge), cutsets.single())
  }

  @Test
  fun `xcfa getMinimalCutsets returns empty when error is unreachable`() {
    val model = xcfa("test") { procedure("main") { (init to final) { skip() } }.start() }

    // The error location is created by the DSL but has no incoming edges.
    assertTrue(model.getMinimalCutsets().isEmpty())
  }

  // -------------------------------------------------------------------------
  // cutsetPartitionSizes tests
  // -------------------------------------------------------------------------

  @Test
  fun `partition sizes for first edge of linear path`() {
    // init --e1--> mid --e2--> error
    // Cutset {e1}: before = {init}, after = {mid, error}
    val init = loc("init", init = true)
    val mid = loc("mid")
    val error = loc("error", error = true)
    val e1 = edge(init, mid)
    val e2 = edge(mid, error)
    val p = proc("p", init, Optional.of(error), setOf(init, mid, error), setOf(e1, e2))

    assertEquals(Pair(1, 2), p.cutsetPartitionSizes(setOf(e1), init, error))
  }

  @Test
  fun `partition sizes for second edge of linear path`() {
    // init --e1--> mid --e2--> error
    // Cutset {e2}: before = {init, mid}, after = {error}
    val init = loc("init", init = true)
    val mid = loc("mid")
    val error = loc("error", error = true)
    val e1 = edge(init, mid)
    val e2 = edge(mid, error)
    val p = proc("p", init, Optional.of(error), setOf(init, mid, error), setOf(e1, e2))

    assertEquals(Pair(2, 1), p.cutsetPartitionSizes(setOf(e2), init, error))
  }

  @Test
  fun `partition sizes for parallel path cutset blocking all exits from source`() {
    // init --e1--> A --e3--> error
    // init --e2--> B --e4--> error
    // Cutset {e1, e2}: before = {init}, after = {A, B, error}
    val init = loc("init", init = true)
    val a = loc("A")
    val b = loc("B")
    val error = loc("error", error = true)
    val e1 = edge(init, a)
    val e2 = edge(init, b)
    val e3 = edge(a, error)
    val e4 = edge(b, error)
    val p = proc("p", init, Optional.of(error), setOf(init, a, b, error), setOf(e1, e2, e3, e4))

    assertEquals(Pair(1, 3), p.cutsetPartitionSizes(setOf(e1, e2), init, error))
  }

  @Test
  fun `partition sizes for parallel path cutset blocking all entries to sink`() {
    // init --e1--> A --e3--> error
    // init --e2--> B --e4--> error
    // Cutset {e3, e4}: before = {init, A, B}, after = {error}
    val init = loc("init", init = true)
    val a = loc("A")
    val b = loc("B")
    val error = loc("error", error = true)
    val e1 = edge(init, a)
    val e2 = edge(init, b)
    val e3 = edge(a, error)
    val e4 = edge(b, error)
    val p = proc("p", init, Optional.of(error), setOf(init, a, b, error), setOf(e1, e2, e3, e4))

    assertEquals(Pair(3, 1), p.cutsetPartitionSizes(setOf(e3, e4), init, error))
  }

  // -------------------------------------------------------------------------
  // Extension helper used in the cycle test above
  // -------------------------------------------------------------------------
  private fun <T> Set<T>.isSubsetOf(other: Set<T>): Boolean = other.containsAll(this)
}
