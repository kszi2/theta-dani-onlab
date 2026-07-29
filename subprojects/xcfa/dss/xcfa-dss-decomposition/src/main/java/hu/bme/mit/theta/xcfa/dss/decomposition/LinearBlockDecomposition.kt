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

import hu.bme.mit.theta.xcfa.model.XcfaEdge
import hu.bme.mit.theta.xcfa.model.XcfaLocation
import hu.bme.mit.theta.xcfa.model.XcfaProcedure

/**
 * `blk_linear` from the DSS paper (Beyer, Kettl & Lemberger, FSE 2024, Sect. 3): cuts a new block
 * boundary at every location that is a branch point, a join point, or a dead end. Loop bodies get
 * their own block with entry = exit = loop head, which falls out of the join-point rule for free (a
 * loop head is always the join of the loop-entry edge and the back edge).
 *
 * Verified against the real CPAchecker source, not just the paper: `LinearBlockNodeDecomposition`
 * itself delegates entirely to an injected `Predicate<CFANode>`, and the *general-purpose*
 * `BlockOperator.isBlockEnd` it's parameterized with has its own Java class defaults
 * (`alwaysAtLoops=true`, `alwaysAtFunctions=true`, join/branch both `false`) that do **not** match
 * this join-or-branch rule at all. What DSS actually runs with instead comes from CPAchecker's
 * `config/dss.properties`, which overrides those defaults to `alwaysAtJoin=true`,
 * `alwaysAtBranch=true`, `alwaysAtProgramExit=true` (plus function-specific flags irrelevant to
 * Theta's current single-procedure-only scope) - i.e. join-or-branch-or-program-exit, matching this
 * rule exactly once the right config layer is the reference point, not the class defaults.
 *
 * [isBlockEnd] defaults to "not exactly one incoming and exactly one outgoing edge" - the same
 * notion of a non-collapsible location used by the existing large-block-encoding pass
 * ([hu.bme.mit.theta.xcfa.passes.LbePass]): everything else (unique in-edge, unique out-edge) is a
 * "snake" location that a full LBE pass would collapse away, and is exactly what should stay inside
 * a block rather than start a new one.
 */
class LinearBlockDecomposition(
  private val isBlockEnd: (XcfaLocation) -> Boolean = ::defaultIsBlockEnd
) : DssBlockDecomposition {

  override fun decompose(procedure: XcfaProcedure): BlockGraph {
    val blocks = mutableListOf<Block>()
    val visitedEdges = mutableSetOf<XcfaEdge>()
    var nextId = 0

    // The block-in-progress that a pending edge would continue, if it turns out not to be a block
    // boundary. Threaded explicitly per traversal branch (rather than one shared mutable
    // accumulator) so that branches pending on the stack can never corrupt each other's state.
    val stack = ArrayDeque<Pair<XcfaEdge, Chain>>()
    for (edge in procedure.initLoc.outgoingEdges) {
      stack.addLast(edge to Chain(procedure.initLoc))
    }

    while (stack.isNotEmpty()) {
      val (edge, chain) = stack.removeLast()
      if (!visitedEdges.add(edge)) continue

      chain.locations.add(edge.source)
      chain.locations.add(edge.target)
      chain.edges.add(edge)

      if (isBlockEnd(edge.target)) {
        blocks.add(
          Block(
            id = "B${nextId++}",
            initialLocation = chain.initialLocation,
            finalLocation = edge.target,
            locations = chain.locations,
            edges = chain.edges,
          )
        )
        for (next in edge.target.outgoingEdges) {
          stack.addLast(next to Chain(edge.target))
        }
      } else {
        // Not a boundary means exactly one outgoing edge (see defaultIsBlockEnd): the chain has a
        // unique continuation, so keep accumulating into the same chain.
        stack.addLast(edge.target.outgoingEdges.single() to chain)
      }
    }

    return BlockGraph.fromBlocksWithoutEdges(blocks)
  }

  private class Chain(val initialLocation: XcfaLocation) {

    val locations: MutableSet<XcfaLocation> = mutableSetOf(initialLocation)
    val edges: MutableSet<XcfaEdge> = mutableSetOf()
  }

  companion object {

    fun defaultIsBlockEnd(location: XcfaLocation): Boolean =
      location.incomingEdges.size != 1 || location.outgoingEdges.size != 1
  }
}
