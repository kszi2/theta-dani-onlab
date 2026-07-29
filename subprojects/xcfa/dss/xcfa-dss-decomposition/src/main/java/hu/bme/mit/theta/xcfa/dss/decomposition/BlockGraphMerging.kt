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

/**
 * One horizontal-merge pass, mirroring CPAchecker's confirmed `HorizontalMergeDecomposition`:
 * groups blocks that are structurally parallel - the exact same `(predecessorIds, successorIds,
 * finalLocation)` triple, e.g. the two arms of an `if`/`else` that rejoin at the same point - and
 * merges each group of more than one into a single block covering all of their locations/edges.
 *
 * All members of such a group necessarily share [Block.initialLocation] too, even though it is not
 * part of the grouping key: for a non-root block, [Block.predecessorIds] being equal forces it (a
 * consistent [BlockGraph] already requires every predecessor's [Block.finalLocation] to equal this
 * block's [Block.initialLocation], so two blocks with the same predecessor set are pinned to the
 * same value); a [BlockGraph] can only ever have one root, so an empty [Block.predecessorIds] can
 * never appear twice.
 *
 * [maxGroupSize] mirrors CPAchecker's optional `largestHorizontalMerge` cap: a group larger than
 * this is left unmerged rather than collapsed into one large block, trading a lower block count for
 * preserving whatever parallelism that group represented. Unbounded (`Int.MAX_VALUE`) by default.
 *
 * Blocks with no structural sibling (a group of size 1) pass through unchanged, including their
 * existing id - a full [BlockGraph] rebuild only actually happens for merged groups, so ids stay
 * stable across passes that do not touch a given block. Merged blocks default
 * [Block.violationConditionLocation] back to their own [Block.finalLocation] - a pre-existing
 * dedicated abstraction point ([BlockGraphInstrumentation]) does not carry a defined meaning once
 * its owning block has been folded into a larger one, so instrumentation is expected to run *after*
 * merging, not before (matching CPAchecker's own pipeline order: decompose, then merge, then
 * instrument).
 */
fun horizontalMergePass(blockGraph: BlockGraph, maxGroupSize: Int = Int.MAX_VALUE): BlockGraph {
  val groups =
    blockGraph.blocks.groupBy { Triple(it.predecessorIds, it.successorIds, it.finalLocation) }

  val passthrough = mutableListOf<Block>()
  val merged = mutableListOf<Block>()
  val idRemap = mutableMapOf<String, String>()
  for (group in groups.values) {
    if (group.size <= 1 || group.size > maxGroupSize) {
      passthrough.addAll(group)
    } else {
      val mergedBlock = mergeParallelGroup(group)
      merged.add(mergedBlock)
      for (member in group) {
        idRemap[member.id] = mergedBlock.id
      }
    }
  }

  if (idRemap.isEmpty()) {
    return blockGraph
  }
  // Every block's own predecessor/successor ids need rewriting, not just the merged ones' - a
  // block that passed through unmerged can still have referenced one of the ids a *different*
  // group just folded away (e.g. its predecessor was itself horizontally merged in this same
  // pass), and a merged block's own predecessorIds/successorIds (copied from its representative
  // member) can reference another group's now-stale id the same way.
  return BlockGraph((passthrough + merged).map { remapBlockIds(it, idRemap) }.toSet())
}

private fun mergeParallelGroup(group: List<Block>): Block {
  val representative = group.first()
  return Block(
    id = group.joinToString("+") { it.id },
    initialLocation = representative.initialLocation,
    finalLocation = representative.finalLocation,
    locations = group.flatMapTo(mutableSetOf()) { it.locations },
    edges = group.flatMapTo(mutableSetOf()) { it.edges },
    predecessorIds = representative.predecessorIds,
    successorIds = representative.successorIds,
  )
}

/**
 * One vertical-merge pass, mirroring CPAchecker's confirmed `VerticalMergeDecomposition`:
 * repeatedly finds a block `X` with exactly one successor `Y` whose *only* predecessor is `X` - an
 * unbranched, un-joined straight-line edge in the block graph, not just in the underlying CFA - and
 * merges the two into one block, `X`'s [Block.predecessorIds] plus `Y`'s [Block.successorIds].
 * Every other block's own predecessor/successor ids that referenced `X` or `Y` are rewritten to the
 * new block's id (CPAchecker's `MergeIDTracker`, done here by rebuilding the working block set with
 * a plain id-substitution map each time a pair merges) - including the merged block's own ids, in
 * the (unusual but legal) case where `X` and `Y` are *also* connected the other way around (`Y`
 * already one of `X`'s own predecessors too), which would otherwise leave a stale reference to an
 * id that no longer exists.
 *
 * Runs to a fixpoint: each merge strictly reduces the block count by one, so this always
 * terminates, regardless of the graph's shape (including graphs with cycles elsewhere - a chain
 * eligible for vertical merging is, by definition, not itself part of one).
 */
fun verticalMergePass(blockGraph: BlockGraph): BlockGraph {
  var blocks = blockGraph.blocks.associateBy { it.id }

  while (true) {
    val pair =
      blocks.values.firstNotNullOfOrNull { x ->
        val successorId = x.successorIds.singleOrNull() ?: return@firstNotNullOfOrNull null
        if (successorId == x.id) return@firstNotNullOfOrNull null
        val y = blocks[successorId] ?: return@firstNotNullOfOrNull null
        if (y.predecessorIds != setOf(x.id)) return@firstNotNullOfOrNull null
        x to y
      } ?: break

    val (x, y) = pair
    val newId = "${x.id}+${y.id}"
    val idRemap = mapOf(x.id to newId, y.id to newId)
    val combined =
      remapBlockIds(
        Block(
          id = newId,
          initialLocation = x.initialLocation,
          finalLocation = y.finalLocation,
          locations = x.locations + y.locations,
          edges = x.edges + y.edges,
          predecessorIds = x.predecessorIds,
          successorIds = y.successorIds,
        ),
        idRemap,
      )
    blocks =
      blocks.values.filter { it.id !in idRemap }.associate { it.id to remapBlockIds(it, idRemap) } +
        (newId to combined)
  }

  return BlockGraph(blocks.values.toSet())
}

private fun remapBlockIds(block: Block, idRemap: Map<String, String>): Block =
  block.copy(
    predecessorIds = block.predecessorIds.map { idRemap[it] ?: it }.toSet(),
    successorIds = block.successorIds.map { idRemap[it] ?: it }.toSet(),
  )

/**
 * Alternates [horizontalMergePass] and [verticalMergePass] - mirroring CPAchecker's confirmed
 * `MergeBlockNodesDecomposition` - until [blockGraph] has at most [targetBlockCount] blocks, or
 * until a full horizontal-then-vertical round makes no further progress at all (whichever comes
 * first: [targetBlockCount] is not always reachable, e.g. a graph with real branching cannot be
 * merged below the number of structurally-independent paths through it without
 * [horizontalMergePass] discarding correctness, which it never does).
 */
fun mergeToTargetBlockCount(
  blockGraph: BlockGraph,
  targetBlockCount: Int,
  maxHorizontalGroupSize: Int = Int.MAX_VALUE,
): BlockGraph {
  var current = blockGraph
  while (current.blocks.size > targetBlockCount) {
    val next = verticalMergePass(horizontalMergePass(current, maxHorizontalGroupSize))
    if (next.blocks.size == current.blocks.size) {
      return next
    }
    current = next
  }
  return current
}
