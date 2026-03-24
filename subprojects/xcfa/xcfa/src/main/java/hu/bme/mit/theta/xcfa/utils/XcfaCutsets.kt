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

import hu.bme.mit.theta.xcfa.model.XCFA
import hu.bme.mit.theta.xcfa.model.XcfaEdge
import hu.bme.mit.theta.xcfa.model.XcfaLocation
import hu.bme.mit.theta.xcfa.model.XcfaProcedure

/**
 * Returns all minimal edge cutsets of the XCFA.
 *
 * For every init procedure that has an error location, computes the minimal edge cutsets between
 * the initial location and the error location, then returns their union. A cutset is a minimal set
 * of edges whose removal makes the error location unreachable from the initial location.
 *
 * @return the set of all minimal edge cutsets across all applicable init procedures
 */
fun XCFA.getMinimalCutsets(): Set<Set<XcfaEdge>> =
  initProcedures
    .flatMap { (proc, _) ->
      proc.errorLoc
        .map { errorLoc -> proc.getMinimalEdgeCutsets(proc.initLoc, errorLoc) }
        .orElse(emptySet())
    }
    .toSet()

/**
 * Returns all minimal edge cutsets between [source] and [sink] in this procedure's control-flow
 * graph.
 *
 * A **cutset** is a set of edges whose removal disconnects [sink] from [source]. A **minimal**
 * cutset has no proper subset that is also a cutset.
 *
 * The algorithm:
 * 1. Finds all simple (no repeated vertex) paths from [source] to [sink] via DFS.
 * 2. Computes all minimal hitting sets of those path-edge-sets: a hitting set must contain at least
 *    one edge from every path, thereby blocking every route to [sink].
 *
 * @param source the location from which reachability is tested (defaults to [initLoc])
 * @param sink the location to be cut off
 * @return the set of minimal cutsets; empty when [sink] is unreachable from [source]
 */
fun XcfaProcedure.getMinimalEdgeCutsets(
  source: XcfaLocation = initLoc,
  sink: XcfaLocation,
): Set<Set<XcfaEdge>> {
  require(source != sink) { "Source and sink must be distinct locations" }
  val paths = findAllSimplePaths(source, sink)
  if (paths.isEmpty()) return emptySet()
  return minimalHittingSets(paths.map { it.toSet() })
}

/**
 * Finds all simple paths (no repeated vertex) from [source] to [sink] via DFS with backtracking.
 */
private fun findAllSimplePaths(source: XcfaLocation, sink: XcfaLocation): List<List<XcfaEdge>> {
  val result = mutableListOf<List<XcfaEdge>>()
  val visitedLocs = mutableSetOf<XcfaLocation>()
  val currentPath = mutableListOf<XcfaEdge>()

  fun dfs(current: XcfaLocation) {
    if (current == sink) {
      result.add(currentPath.toList())
      return
    }
    if (!visitedLocs.add(current)) return
    for (edge in current.outgoingEdges) {
      currentPath.add(edge)
      dfs(edge.target)
      currentPath.removeLast()
    }
    visitedLocs.remove(current)
  }

  dfs(source)
  return result
}

/**
 * Computes all minimal hitting sets of a collection of edge sets.
 *
 * A **hitting set** H satisfies H ∩ S ≠ ∅ for every S in [pathSets]. A **minimal** hitting set has
 * no proper subset that is also a hitting set.
 *
 * Uses a recursive branch-and-bound approach: branches on an element from the smallest chosen set
 * and recurses on the remaining uncovered sets. Non-minimal candidates are discarded after each
 * branch.
 */
private fun minimalHittingSets(pathSets: List<Set<XcfaEdge>>): Set<Set<XcfaEdge>> {
  if (pathSets.isEmpty()) return setOf(emptySet())

  // Branch on the path with fewest edges to minimise the branching factor
  val chosen = pathSets.minByOrNull { it.size }!!
  val result = mutableSetOf<Set<XcfaEdge>>()

  for (edge in chosen) {
    // Recurse on paths not yet covered by this edge
    val remaining = pathSets.filter { edge !in it }
    for (sub in minimalHittingSets(remaining)) {
      val candidate = sub + edge
      // Keep only if no existing result strictly dominates candidate
      if (result.none { existing -> existing !== candidate && existing.isSubsetOf(candidate) }) {
        result.removeAll { existing -> existing !== candidate && candidate.isSubsetOf(existing) }
        result.add(candidate)
      }
    }
  }

  return result
}

/** Returns true iff this set is a subset of [other]. */
private fun <T> Set<T>.isSubsetOf(other: Set<T>): Boolean = other.containsAll(this)

/**
 * Returns the number of locations strictly before and strictly after a cutset.
 * - **Before**: locations reachable from [source] by traversing only edges not in [cutset].
 * - **After**: locations from which [sink] is reachable by traversing only edges not in [cutset]
 *   (i.e., backward-reachable from [sink] without crossing [cutset] edges).
 *
 * @param cutset the set of edges forming the cut
 * @param source the location from which forward reachability is measured (defaults to [initLoc])
 * @param sink the location from which backward reachability is measured
 * @return a pair where [Pair.first] is the before-count and [Pair.second] is the after-count
 */
fun XcfaProcedure.cutsetPartitionSizes(
  cutset: Set<XcfaEdge>,
  source: XcfaLocation = initLoc,
  sink: XcfaLocation,
): Pair<Int, Int> {
  val before = locationsReachableWithout(source, cutset, forward = true)
  val after = locationsReachableWithout(sink, cutset, forward = false)
  return Pair(before.size, after.size)
}

/**
 * BFS from [start], traversing edges in the forward or backward direction while skipping all edges
 * in [blockedEdges]. Returns the set of locations visited (including [start] itself).
 */
private fun locationsReachableWithout(
  start: XcfaLocation,
  blockedEdges: Set<XcfaEdge>,
  forward: Boolean,
): Set<XcfaLocation> {
  val visited = mutableSetOf<XcfaLocation>()
  val queue = ArrayDeque<XcfaLocation>()
  queue.add(start)
  while (queue.isNotEmpty()) {
    val loc = queue.removeFirst()
    if (!visited.add(loc)) continue
    val edges = if (forward) loc.outgoingEdges else loc.incomingEdges
    for (edge in edges) {
      if (edge !in blockedEdges) queue.add(if (forward) edge.target else edge.source)
    }
  }
  return visited
}
