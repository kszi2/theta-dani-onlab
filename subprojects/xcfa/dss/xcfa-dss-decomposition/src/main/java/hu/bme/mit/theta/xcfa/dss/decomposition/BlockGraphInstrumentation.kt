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
package hu.bme.mit.theta.xcfa.dss.decomposition

import hu.bme.mit.theta.xcfa.model.NopLabel
import hu.bme.mit.theta.xcfa.model.XcfaEdge
import hu.bme.mit.theta.xcfa.model.XcfaLocation
import hu.bme.mit.theta.xcfa.model.XcfaProcedure

/**
 * Gives every block (that needs one) a dedicated abstraction point, distinct from the location
 * where it meets its neighbours.
 *
 * A block's [Block.finalLocation] is, for any non-root block, also some other block's
 * [Block.initialLocation] - running predicate abstraction directly there would mean abstracting a
 * location that is simultaneously "owned" by the next block too. Instead, for every block whose
 * final location has somewhere to go (see below), this appends a synthetic no-op "ghost" edge to a
 * freshly created location, and that new location becomes the block's
 * [Block.violationConditionLocation] - the point where a worker will later be told to place an
 * abstraction (the `cpa.predicate.blk.alwaysAtGivenNodes`-equivalent from the DSS plan's §3).
 * [XcfaProcedure] is copied to include the new locations/edges in [XcfaProcedure.locs]/
 * [XcfaProcedure.edges]; its internal `parent` back-reference (set only during
 * [hu.bme.mit.theta.xcfa.model.XcfaProcedureBuilder.build] and, as far as this module can tell,
 * never read afterward) is not preserved by that copy - revisit if something downstream turns out
 * to depend on it.
 *
 * The block graph's topology (which locations delimit which blocks, and how blocks connect to each
 * other) is unchanged: [Block.finalLocation] still names the original, shared location, and
 * [Block.predecessorIds]/[Block.successorIds] are untouched.
 *
 * Mirrors CPAchecker's `BlockGraphModification.instrumentCFA`, with one of its exclusions carried
 * over: a block ending in a genuine dead end (no outgoing edges at all, e.g. the procedure's
 * `final`/`error` location) gets no ghost edge, since no successor block is ever waiting on a
 * summary from there - [Block.isAbstractionPossible] is `false` for such blocks. The exclusions
 * CPAchecker applies for call-site and main-entry locations are *not* ported yet (see
 * dss-in-theta-plan.md's open questions): XCFA's call model differs enough from CPAchecker's CFA (a
 * single edge carrying an `InvokeLabel`, not separate call/summary/return edges) that porting that
 * nuance needs its own investigation once the per-block analysis adapter (plan §3) is built.
 *
 * Must run after decomposition, on that decomposition's own result - re-decomposing an already
 * instrumented procedure is not supported (the ghost edges would be picked up as ordinary CFG
 * structure). This mutates the given [procedure]'s existing locations in place (adding the new
 * ghost edges to [XcfaLocation.outgoingEdges]/[XcfaLocation.incomingEdges]), since that is what any
 * traversal over the procedure - including [LinearBlockDecomposition] itself - actually reads;
 * [XcfaLocation] has no usable `copy()` for this (its adjacency sets are plain, freshly-initialized
 * `val`s, not `data class` constructor parameters).
 */
object BlockGraphInstrumentation {

  data class Modification(val procedure: XcfaProcedure, val blockGraph: BlockGraph)

  fun instrumentForAbstraction(procedure: XcfaProcedure, blockGraph: BlockGraph): Modification {
    val ghostEdgeByFinalLocation = mutableMapOf<XcfaLocation, XcfaEdge>()

    for (finalLocation in blockGraph.blocks.map(Block::finalLocation).toSet()) {
      if (finalLocation.outgoingEdges.isEmpty()) {
        continue
      }
      val ghostLocation = XcfaLocation("${finalLocation.name}__ghost", metadata = GhostEdgeMetadata)
      val ghostEdge = XcfaEdge(finalLocation, ghostLocation, NopLabel, GhostEdgeMetadata)
      finalLocation.outgoingEdges.add(ghostEdge)
      ghostLocation.incomingEdges.add(ghostEdge)
      ghostEdgeByFinalLocation[finalLocation] = ghostEdge
    }

    val instrumentedBlocks =
      blockGraph.blocks.map { block ->
        val ghostEdge = ghostEdgeByFinalLocation[block.finalLocation] ?: return@map block
        block.copy(
          locations = block.locations + ghostEdge.target,
          edges = block.edges + ghostEdge,
          violationConditionLocation = ghostEdge.target,
        )
      }

    val instrumentedProcedure =
      procedure.copy(
        locs = procedure.locs + ghostEdgeByFinalLocation.values.map { it.target },
        edges = procedure.edges + ghostEdgeByFinalLocation.values,
      )

    return Modification(instrumentedProcedure, BlockGraph(instrumentedBlocks.toSet()))
  }
}
