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
package hu.bme.mit.theta.xcfa.dss.analysis

import hu.bme.mit.theta.core.stmt.Stmts.Assume
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.xcfa.dss.decomposition.Block
import hu.bme.mit.theta.xcfa.model.EmptyMetaData
import hu.bme.mit.theta.xcfa.model.StmtLabel
import hu.bme.mit.theta.xcfa.model.XCFA
import hu.bme.mit.theta.xcfa.model.XcfaEdge
import hu.bme.mit.theta.xcfa.model.XcfaGlobalVar
import hu.bme.mit.theta.xcfa.model.XcfaLocation
import hu.bme.mit.theta.xcfa.model.XcfaProcedure
import java.util.Optional

/**
 * The result of [extractBlockXcfa]: the standalone [xcfa] itself, plus [locationMapping] from the
 * original [Block]'s locations to their clones inside [xcfa] - needed because [xcfa] does not share
 * location identity with the source procedure (see [extractBlockXcfa]'s doc), so looking up e.g. a
 * postcondition for [Block.violationConditionLocation] in a [runWorkerConfig] result requires the
 * cloned location, not the original.
 */
data class BlockXcfaExtraction(
  val xcfa: XCFA,
  val locationMapping: Map<XcfaLocation, XcfaLocation>,
)

/**
 * Extracts [block] into a standalone, checkable [XCFA] containing only that block's own
 * locations/edges, with [Block.initialLocation] as the entry point - "block = whole program" for
 * whatever runs against the result (e.g. [runWorkerConfig]). If [precondition] is given, a
 * synthetic `assume(precondition)` edge is prepended before the block's real entry, so the checker
 * explores from states satisfying it instead of from top - the `unpackPost` half of the plan's §3
 * pack/unpack pair, implemented as an ordinary assume rather than by seeding a custom initial
 * state, since Theta's predicate-domain init function is hardcoded to start from `True()`
 * (`getPredXcfaInitFunc`) and reaching into that is unnecessary when the existing edge machinery
 * already does the job.
 *
 * Theta's predicate CEGAR has no runtime equivalent of CPAchecker's
 * `cpa.predicate.blk.alwaysAtGivenNodes`: `getPredXcfaTransFunc` (confirmed by reading it) computes
 * a fresh abstraction after *every* edge, unconditionally - "large blocks" are a static property of
 * the graph (what `LbePass` collapses away before analysis), not a per-run decision the CEGAR loop
 * makes. So there is no way to tell a checker "explore this whole procedure, but only abstract at
 * this one location" the way CPAchecker's worker config does. Extracting each block into its own
 * tiny standalone program and running the ordinary checker on it sidesteps that entirely: the
 * checker abstracts at every location *inside* the block too, which is sound (just less precise/
 * performant than CPAchecker's exact block granularity) - an accepted v1 gap, matching the plan's
 * own "profile before assuming the port is slow" guidance rather than something to fix
 * pre-emptively.
 *
 * Clones every [XcfaLocation]/[XcfaEdge] the block owns, rather than reusing the originals as-is.
 * This is not optional: [XcfaLocation.incomingEdges]/[XcfaLocation.outgoingEdges] are mutable,
 * freshly-initialized-per-instance sets - not `data class` constructor parameters - so the
 * *original* location objects still carry every edge from the *whole* source procedure, regardless
 * of what this function's own [XcfaProcedure.locs]/[XcfaProcedure.edges] declare. The real
 * ARG-building traversal reads location adjacency directly, not the owning procedure's edge set, so
 * reusing locations unmodified does not isolate the block at all - confirmed by a test failure
 * during development: a block with no error location of its own was found "unsafe" by walking
 * straight through a shared, unrestricted location into a neighbouring block's error location.
 * [sourceProcedure] supplies `vars` (passed through unfiltered, not narrowed to just what the
 * block's own edges reference - correctness over a minor, unnecessary optimization) since [Block]
 * does not track its own variable subset.
 */
fun extractBlockXcfa(
  sourceProcedure: XcfaProcedure,
  block: Block,
  globalVars: Set<XcfaGlobalVar> = emptySet(),
  precondition: Expr<BoolType>? = null,
): BlockXcfaExtraction {
  val name = "${sourceProcedure.name}__${block.id}"

  val clonedLocations: Map<XcfaLocation, XcfaLocation> =
    block.locations.associateWith { original ->
      XcfaLocation(
        original.name,
        original.initial,
        original.final,
        original.error,
        original.metadata,
      )
    }
  val clonedEdges: MutableSet<XcfaEdge> =
    block.edges
      .map { original ->
        val clone =
          XcfaEdge(
            clonedLocations.getValue(original.source),
            clonedLocations.getValue(original.target),
            original.label,
            original.metadata,
          )
        clone.source.outgoingEdges.add(clone)
        clone.target.incomingEdges.add(clone)
        clone
      }
      .toMutableSet()

  val allLocations = clonedLocations.values.toMutableSet()
  var entryLoc = clonedLocations.getValue(block.initialLocation)
  if (precondition != null) {
    val syntheticEntry = XcfaLocation("${name}__entry", metadata = EmptyMetaData)
    val assumeEdge =
      XcfaEdge(syntheticEntry, entryLoc, StmtLabel(Assume(precondition)), EmptyMetaData)
    syntheticEntry.outgoingEdges.add(assumeEdge)
    entryLoc.incomingEdges.add(assumeEdge)
    clonedEdges.add(assumeEdge)
    allLocations.add(syntheticEntry)
    entryLoc = syntheticEntry
  }

  val blockProcedure =
    XcfaProcedure(
      name = name,
      params = emptyList(),
      vars = sourceProcedure.vars,
      locs = allLocations,
      edges = clonedEdges,
      initLoc = entryLoc,
      finalLoc = clonedLocations.values.firstOrNull { it.final }.toOptional(),
      errorLoc = clonedLocations.values.firstOrNull { it.error }.toOptional(),
    )
  // XCFA's public constructor only accepts XcfaProcedureBuilders, which would re-run the whole
  // pass/optimize pipeline - build an empty container instead and swap in the already-built
  // procedure via `recreate`.
  val xcfa =
    XCFA(name, globalVars).recreate(setOf(blockProcedure), listOf(blockProcedure to emptyList()))
  return BlockXcfaExtraction(xcfa, clonedLocations)
}

private fun <T : Any> T?.toOptional(): Optional<T> = Optional.ofNullable(this)
