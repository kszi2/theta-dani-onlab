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

import hu.bme.mit.theta.core.decl.VarDecl
import hu.bme.mit.theta.core.model.Valuation
import hu.bme.mit.theta.core.stmt.Stmts.Assign
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.Type
import hu.bme.mit.theta.xcfa.model.EmptyMetaData
import hu.bme.mit.theta.xcfa.model.NopLabel
import hu.bme.mit.theta.xcfa.model.SequenceLabel
import hu.bme.mit.theta.xcfa.model.StmtLabel
import hu.bme.mit.theta.xcfa.model.XCFA
import hu.bme.mit.theta.xcfa.model.XcfaEdge
import hu.bme.mit.theta.xcfa.model.XcfaLabel
import hu.bme.mit.theta.xcfa.model.XcfaLocation
import hu.bme.mit.theta.xcfa.model.XcfaProcedure
import java.util.IdentityHashMap
import java.util.Optional

/**
 * Splits an XCFA into two parts based on a [CutsetWithPartitionSizes].
 *
 * Returns `Pair(before, after)` where:
 * - **before**: contains every location forward-reachable from `initLoc` without crossing the
 *   cutset. Each cutset edge `s → t` is replaced by `s → cut_final` keeping the original edge
 *   label, so the computation leading up to the cut is preserved.
 * - **after**: contains every location backward-reachable from `errorLoc` without crossing the
 *   cutset. A single new `cut_init` location is added with a [NopLabel] edge to the target of
 *   each cutset edge, representing the nondeterministic entry point after the cut.
 *
 * Both halves receive the same global variables as the original XCFA. The procedure that owns
 * the cutset edges is split; all other procedures are omitted from the result.
 *
 * @throws IllegalArgumentException if the XCFA has no init procedures, or the init procedure
 *   that owns the cutset has no error location.
 */
fun XCFA.splitByCutset(cutset: CutsetWithPartitionSizes): Pair<XCFA, XCFA> {
  require(initProcedures.isNotEmpty()) { "XCFA has no init procedures" }

  // Find the init procedure whose edge set contains all cutset edges.
  val (proc, params) =
    initProcedures.first { (p, _) ->
      cutset.cutset.isEmpty() || cutset.cutset.all { edge -> edge in p.edges }
    }

  require(proc.errorLoc.isPresent) { "Init procedure '${proc.name}' has no error location" }
  val errorLoc = proc.errorLoc.get()

  val beforeLocs = locationsReachableWithout(proc.initLoc, cutset.cutset, forward = true)
  val afterLocs = locationsReachableWithout(errorLoc, cutset.cutset, forward = false)

  return Pair(
    buildBeforeXcfa(this, proc, params, cutset.cutset, beforeLocs),
    buildAfterXcfa(this, proc, params, cutset.cutset, afterLocs),
  )
}

// ---------------------------------------------------------------------------
// Before-XCFA builder
// ---------------------------------------------------------------------------

private fun buildBeforeXcfa(
  original: XCFA,
  proc: XcfaProcedure,
  params: List<Expr<*>>,
  cutset: Set<XcfaEdge>,
  beforeLocs: Set<XcfaLocation>,
): XCFA {
  val locMap = freshCopies(proc, beforeLocs)

  // New error location representing "the cutset was reached".
  val cutFinalLoc =
    XcfaLocation("${proc.name}_cut_final", error = true, metadata = EmptyMetaData)

  val allLocs: MutableSet<XcfaLocation> = LinkedHashSet(locMap.values)
  allLocs.add(cutFinalLoc)

  val edgeSet = mutableSetOf<XcfaEdge>()

  for (edge in proc.edges) {
    val freshSource = locMap[edge.source] ?: continue
    if (edge in cutset) {
      // Replace the cutset edge with source → cut_final, keeping the original label
      // so the computation leading up to the cut is included in the before-XCFA.
      addEdge(XcfaEdge(freshSource, cutFinalLoc, edge.label, edge.metadata), edgeSet)
    } else {
      val freshTarget = locMap[edge.target] ?: continue
      addEdge(XcfaEdge(freshSource, freshTarget, edge.label, edge.metadata), edgeSet)
    }
  }

  val beforeProc =
    XcfaProcedure(
      name = "${proc.name}_before",
      params = proc.params,
      vars = proc.vars,
      locs = allLocs,
      edges = edgeSet,
      initLoc = locMap[proc.initLoc]!!,
      finalLoc = Optional.empty(),
      errorLoc = Optional.of(cutFinalLoc),
    )

  return wrapInXcfa("${original.name}_before", original, beforeProc, params)
}

// ---------------------------------------------------------------------------
// After-XCFA builder
// ---------------------------------------------------------------------------

private fun buildAfterXcfa(
  original: XCFA,
  proc: XcfaProcedure,
  params: List<Expr<*>>,
  cutset: Set<XcfaEdge>,
  afterLocs: Set<XcfaLocation>,
): XCFA {
  val locMap = freshCopies(proc, afterLocs)

  // New initial location representing "entering the after-partition from the cut".
  val cutInitLoc =
    XcfaLocation("${proc.name}_cut_init", initial = true, metadata = EmptyMetaData)

  val allLocs: MutableSet<XcfaLocation> = LinkedHashSet()
  allLocs.add(cutInitLoc)
  allLocs.addAll(locMap.values)

  val edgeSet = mutableSetOf<XcfaEdge>()

  // One NopLabel entry edge per cutset edge whose target is in the after-partition.
  for (edge in cutset) {
    val freshTarget = locMap[edge.target] ?: continue
    addEdge(XcfaEdge(cutInitLoc, freshTarget, NopLabel, edge.metadata), edgeSet)
  }

  // All original edges that stay entirely within the after-partition.
  for (edge in proc.edges) {
    if (edge in cutset) continue
    val freshSource = locMap[edge.source] ?: continue
    val freshTarget = locMap[edge.target] ?: continue
    addEdge(XcfaEdge(freshSource, freshTarget, edge.label, edge.metadata), edgeSet)
  }

  val afterProc =
    XcfaProcedure(
      name = "${proc.name}_after",
      params = proc.params,
      vars = proc.vars,
      locs = allLocs,
      edges = edgeSet,
      initLoc = cutInitLoc,
      finalLoc = proc.finalLoc.flatMap { Optional.ofNullable(locMap[it]) },
      errorLoc = proc.errorLoc.flatMap { Optional.ofNullable(locMap[it]) },
    )

  return wrapInXcfa("${original.name}_after", original, afterProc, params)
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

/**
 * Creates fresh [XcfaLocation] copies for every location in [partition] using an
 * [IdentityHashMap] so that two equal-but-distinct locations in the original map to separate
 * copies. Fresh copies start with empty [XcfaLocation.outgoingEdges] /
 * [XcfaLocation.incomingEdges], preventing mutation of the original XCFA's locations.
 */
private fun freshCopies(
  proc: XcfaProcedure,
  partition: Set<XcfaLocation>,
): IdentityHashMap<XcfaLocation, XcfaLocation> {
  val map = IdentityHashMap<XcfaLocation, XcfaLocation>()
  for (loc in partition) {
    map[loc] = XcfaLocation(loc.name, loc.initial, loc.final, loc.error, loc.metadata)
  }
  return map
}

/** Adds [edge] to [edgeSet] and wires it into both endpoints' adjacency lists. */
private fun addEdge(edge: XcfaEdge, edgeSet: MutableSet<XcfaEdge>) {
  edgeSet.add(edge)
  edge.source.outgoingEdges.add(edge)
  edge.target.incomingEdges.add(edge)
}

/**
 * Returns a copy of this XCFA where every entry edge of the (single) init procedure has the
 * given [valuation]'s variable assignments prepended. Edges whose source is the init location
 * get a [SequenceLabel] of `[x := v, ..., originalLabel]`; all other edges are unchanged.
 *
 * If [valuation] contains no declared variables the original XCFA is returned as-is.
 */
fun XCFA.withEntryAssignments(valuation: Valuation): XCFA {
  val (proc, params) = initProcedures.single()

  @Suppress("UNCHECKED_CAST")
  val assignLabels: List<XcfaLabel> = valuation.decls.mapNotNull { decl ->
    val value = valuation.eval(decl).orElse(null) ?: return@mapNotNull null
    StmtLabel(Assign(decl as VarDecl<Type>, value as Expr<Type>))
  }
  if (assignLabels.isEmpty()) return this

  val newEdges = mutableSetOf<XcfaEdge>()
  for (edge in proc.edges) {
    if (edge.source === proc.initLoc) {
      val newLabel = SequenceLabel(assignLabels + edge.label)
      val newEdge = XcfaEdge(edge.source, edge.target, newLabel, edge.metadata)
      edge.source.outgoingEdges.remove(edge)
      edge.target.incomingEdges.remove(edge)
      edge.source.outgoingEdges.add(newEdge)
      edge.target.incomingEdges.add(newEdge)
      newEdges.add(newEdge)
    } else {
      newEdges.add(edge)
    }
  }

  val initProcSet = initProcedures.mapTo(HashSet()) { it.first }
  val nonInitProcs = procedures.filter { it !in initProcSet }
  val newProc = proc.copy(edges = newEdges)
  return XCFA(name, globalVars).also { xcfa ->
    newProc.parent = xcfa
    nonInitProcs.forEach { it.parent = xcfa }
    xcfa.recreate(setOf(newProc) + nonInitProcs, listOf(Pair(newProc, params)))
  }
}

/**
 * Wraps a single manually-built [XcfaProcedure] in a new [XCFA], sharing the global variables
 * of [original]. Uses [XCFA.recreate] to inject the procedure without re-running optimisation
 * passes.
 */
private fun wrapInXcfa(
  name: String,
  original: XCFA,
  proc: XcfaProcedure,
  params: List<Expr<*>>,
): XCFA {
  val xcfa = XCFA(name, original.globalVars)
  proc.parent = xcfa
  // Non-init procedures (threads, helpers) are not split; carry them over unchanged
  // so that StartLabel resolution can find them during analysis.
  val initProcSet = original.initProcedures.mapTo(HashSet()) { it.first }
  val nonInitProcs = original.procedures.filter { it !in initProcSet }
  nonInitProcs.forEach { it.parent = xcfa }
  xcfa.recreate(setOf(proc) + nonInitProcs, listOf(Pair(proc, params)))
  return xcfa
}
