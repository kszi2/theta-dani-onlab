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
import java.util.IdentityHashMap
import java.util.Optional
import org.junit.jupiter.api.Test

/**
 * Prints human-readable before/after splits of three example XCFAs.
 *
 * Each @Test builds one XCFA, computes the most balanced cutset, splits it, and prints the
 * original + both halves to stdout. Run with Gradle --info to see the output.
 *
 * JUnit creates a fresh instance per @Test, so [edgeLabelMap] is isolated between examples.
 */
class XcfaSplitterDemoTest {

  // ---------------------------------------------------------------------------
  // Per-instance state (fresh for every @Test because JUnit creates a new instance)
  // ---------------------------------------------------------------------------

  /** Stores a readable display label for each edge (we use NopLabel everywhere for simplicity). */
  private val edgeLabelMap = IdentityHashMap<XcfaEdge, String>()

  /** Set to the active cutset before printing so cut-edge labels resolve unambiguously. */
  private var activeCutset: Set<XcfaEdge> = emptySet()

  private fun mkEdge(src: XcfaLocation, dst: XcfaLocation, display: String): XcfaEdge {
    val e = XcfaEdge(src, dst, NopLabel, EmptyMetaData)
    src.outgoingEdges.add(e)
    dst.incomingEdges.add(e)
    edgeLabelMap[e] = display
    return e
  }

  private fun loc(
    name: String,
    init: Boolean = false,
    error: Boolean = false,
    final: Boolean = false,
  ) = XcfaLocation(name, initial = init, error = error, final = final, metadata = EmptyMetaData)

  private fun xcfaOf(proc: XcfaProcedure): XCFA {
    val xcfa = XCFA(proc.name, emptySet())
    proc.parent = xcfa
    xcfa.recreate(setOf(proc), listOf(Pair(proc, emptyList())))
    return xcfa
  }

  // ---------------------------------------------------------------------------
  // Example 1 — Linear path
  //
  //   init --[x:=0]--> loc_A --[x:=x+1]--> loc_B --[assume(x==1)]--> error
  //
  //   3 cutsets; most balanced: {x:=x+1}  before=2  after=2
  // ---------------------------------------------------------------------------

  @Test
  fun `example 1 - linear path`() {
    val init  = loc("init",  init  = true)
    val locA  = loc("loc_A")
    val locB  = loc("loc_B")
    val error = loc("error", error = true)

    val e1 = mkEdge(init,  locA,  "x := 0")
    val e2 = mkEdge(locA,  locB,  "x := x+1")
    val e3 = mkEdge(locB,  error, "assume(x==1)")

    val proc = XcfaProcedure(
      "main", emptyList(), emptySet(),
      setOf(init, locA, locB, error), setOf(e1, e2, e3),
      init, Optional.empty(), Optional.of(error),
    )
    printSplitDemo("EXAMPLE 1 — Linear path", xcfaOf(proc))
  }

  // ---------------------------------------------------------------------------
  // Example 2 — Diamond (two parallel branches)
  //
  //          [assume(flag)]           [err_path_A]
  //   init ─────────────────> A ───────────────────> error
  //     │                                               ↑
  //     │ [assume(!flag)]         [err_path_B]          │
  //     └──────────────────> B ─────────────────────────┘
  //
  //   4 cutsets; most balanced: a cross-cut  before=2  after=2
  // ---------------------------------------------------------------------------

  @Test
  fun `example 2 - diamond parallel paths`() {
    val init  = loc("init",  init  = true)
    val a     = loc("A")
    val b     = loc("B")
    val error = loc("error", error = true)

    val e1 = mkEdge(init, a,     "assume(flag)")
    val e2 = mkEdge(init, b,     "assume(!flag)")
    val e3 = mkEdge(a,    error, "err_path_A")
    val e4 = mkEdge(b,    error, "err_path_B")

    val proc = XcfaProcedure(
      "main", emptyList(), emptySet(),
      setOf(init, a, b, error), setOf(e1, e2, e3, e4),
      init, Optional.empty(), Optional.of(error),
    )
    printSplitDemo("EXAMPLE 2 — Diamond (parallel paths)", xcfaOf(proc))
  }

  // ---------------------------------------------------------------------------
  // Example 3 — Asymmetric paths (one direct shortcut, one long route)
  //
  //   init ──[shortcut]─────────────────────────────> error
  //     │                                               ↑
  //     │ [step1]       [step2]       [step3]           │
  //     └──────> A ──────────> B ───────────────────────┘
  //
  //   3 cutsets (shortcut must be in all of them); most balanced: {shortcut, step2} before=2 after=2
  // ---------------------------------------------------------------------------

  @Test
  fun `example 3 - asymmetric paths`() {
    val init  = loc("init",  init  = true)
    val a     = loc("A")
    val b     = loc("B")
    val error = loc("error", error = true)

    val e1 = mkEdge(init, a,     "step1")
    val e2 = mkEdge(a,    b,     "step2")
    val e3 = mkEdge(b,    error, "step3")
    val e4 = mkEdge(init, error, "shortcut")

    val proc = XcfaProcedure(
      "main", emptyList(), emptySet(),
      setOf(init, a, b, error), setOf(e1, e2, e3, e4),
      init, Optional.empty(), Optional.of(error),
    )
    printSplitDemo("EXAMPLE 3 — Asymmetric paths", xcfaOf(proc))
  }

  // ---------------------------------------------------------------------------
  // Shared demo driver
  // ---------------------------------------------------------------------------

  private fun printSplitDemo(title: String, xcfa: XCFA) {
    val cutsets = xcfa.getMinimalCutsetsWithPartitionSizes()
    val best    = cutsets.mostBalancedCutset()!!
    activeCutset = best.cutset
    val (before, after) = xcfa.splitByCutset(best)

    println(buildString {
      appendLine()
      appendLine("═══════════════════════════════════════════════════════")
      appendLine("  $title")
      appendLine("═══════════════════════════════════════════════════════")
      appendLine()
      appendLine("ORIGINAL XCFA  \"${xcfa.name}\"")
      appendLine(xcfaText(xcfa))

      appendLine("All cutsets (sorted by 'before' count):")
      for (cs in cutsets) {
        val diff   = kotlin.math.abs(cs.before - cs.after)
        val marker = if (cs == best) " ◄ most balanced" else ""
        appendLine("  ${cs.cutset.map { displayLabel(it) }.joinToString(", ", "[", "]")}" +
                   "  before=${cs.before}  after=${cs.after}  |diff|=$diff$marker")
      }

      appendLine()
      appendLine("─── Splitting at best cutset ───────────────────────────")
      appendLine()
      appendLine("BEFORE-XCFA  \"${before.name}\"")
      appendLine(xcfaText(before))
      appendLine("AFTER-XCFA   \"${after.name}\"")
      appendLine(xcfaText(after))
      appendLine("═══════════════════════════════════════════════════════")
    })
  }

  // ---------------------------------------------------------------------------
  // Formatting helpers
  // ---------------------------------------------------------------------------

  private fun displayLabel(e: XcfaEdge): String {
    edgeLabelMap[e]?.let { return it }
    // Split creates new XcfaEdge objects — match by endpoint names for regular edges
    edgeLabelMap.entries
      .firstOrNull { it.key.source.name == e.source.name && it.key.target.name == e.target.name }
      ?.value?.let { return it }
    // Cut edges in the before-XCFA land on a fresh cut_final.
    // Restrict lookup to the active cutset to resolve ambiguity when multiple edges share a source.
    if (e.target.final) {
      activeCutset
        .firstOrNull { it.source.name == e.source.name }
        ?.let { edgeLabelMap[it] }
        ?.let { return it }
    }
    return e.label.toString()
  }

  private fun locFlag(l: XcfaLocation) = buildString {
    if (l.initial) append(" {init}")
    if (l.final)   append(" {final}")
    if (l.error)   append(" {error}")
  }

  private fun xcfaText(xcfa: XCFA): String = buildString {
    for ((proc, _) in xcfa.initProcedures) {
      appendLine("  Procedure : ${proc.name}")
      appendLine("  Locations :")
      for (loc in proc.locs) appendLine("    ${loc.name}${locFlag(loc)}")
      appendLine("  Edges     :")
      for (edge in proc.edges) {
        appendLine("    ${edge.source.name} ──[${displayLabel(edge)}]──> ${edge.target.name}")
      }
      appendLine("  initLoc   = ${proc.initLoc.name}")
      appendLine("  finalLoc  = ${proc.finalLoc.map { it.name }.orElse("(none)")}")
      appendLine("  errorLoc  = ${proc.errorLoc.map { it.name }.orElse("(none)")}")
    }
  }
}
