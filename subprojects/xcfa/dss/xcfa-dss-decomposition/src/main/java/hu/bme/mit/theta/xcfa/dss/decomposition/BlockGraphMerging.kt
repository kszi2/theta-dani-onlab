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
 * CPAchecker's `HorizontalMergeDecomposition.NO_MERGE_LIMIT`: no size limit on horizontal merges.
 */
const val NO_MERGE_LIMIT = -1

/**
 * Hands out the ids of merged blocks, mirroring CPAchecker's naming: `MH<n>` for horizontal merges
 * and `MV<n>` for vertical merges, each with its own counter that keeps counting across passes (in
 * CPAchecker the counter is a field of the long-lived `HorizontalMergeDecomposition`/
 * `VerticalMergeDecomposition` instance). [startingAfter] continues the numbering of a graph that
 * may already contain merged blocks, so independent calls never produce colliding ids.
 */
class MergeIdGenerator(private var horizontal: Int = 0, private var vertical: Int = 0) {

  fun nextHorizontal(): String = "MH${horizontal++}"

  fun nextVertical(): String = "MV${vertical++}"

  companion object {

    fun startingAfter(blockGraph: BlockGraph): MergeIdGenerator {
      fun next(prefix: String) =
        blockGraph.blocks
          .filter { it.id.startsWith(prefix) }
          .mapNotNull { it.id.removePrefix(prefix).toIntOrNull() }
          .maxOrNull()
          ?.plus(1) ?: 0
      return MergeIdGenerator(next("MH"), next("MV"))
    }
  }
}

/**
 * CPAchecker sorts the block nodes by id between merge passes, which makes merging deterministic.
 */
private fun Collection<Block>.sortedById(): List<Block> = sortedBy { it.id }

/**
 * One horizontal-merge pass, mirroring CPAchecker's
 * `HorizontalMergeDecomposition.mergeHorizontally`: groups blocks that have the exact same
 * `(predecessorIds, successorIds, finalLocation)` scope (for example the two arms of an `if`/`else`
 * that rejoin at the same location) and merges each group of more than one block into a single
 * block `MH<n>` covering all of their locations and edges.
 *
 * As in CPAchecker:
 * - groups are visited in the order of their (id-sorted) members, and the pass stops as soon as the
 *   graph has at most [targetBlockCount] blocks;
 * - [largestHorizontalMerge] limits merging by block *size*: a group is skipped when more than one
 *   of its blocks has more than [largestHorizontalMerge] locations ([NO_MERGE_LIMIT] disables the
 *   limit).
 *
 * All members of such a group necessarily share [Block.initialLocation] too: for a non-root block,
 * equal [Block.predecessorIds] force it, and a [BlockGraph] has only one root.
 *
 * Merged blocks default [Block.violationConditionLocation] back to their own [Block.finalLocation],
 * so [BlockGraphInstrumentation] has to run after merging (CPAchecker's own order: decompose,
 * merge, instrument).
 */
fun horizontalMergePass(
  blockGraph: BlockGraph,
  targetBlockCount: Int = 0,
  largestHorizontalMerge: Int = NO_MERGE_LIMIT,
  ids: MergeIdGenerator = MergeIdGenerator.startingAfter(blockGraph),
): BlockGraph {
  val groups =
    LinkedHashMap<Triple<Set<String>, Set<String>, Any>, MutableList<Block>>().apply {
      for (block in blockGraph.blocks.sortedById()) {
        getOrPut(Triple(block.predecessorIds, block.successorIds, block.finalLocation)) {
            mutableListOf()
          }
          .add(block)
      }
    }

  val idRemap = mutableMapOf<String, String>()
  var blockCount = blockGraph.blocks.size
  for ((scope, group) in groups.entries.toList()) {
    if (blockCount <= targetBlockCount) break
    if (group.size <= 1) continue
    val largeBlocks = group.count { it.locations.size > largestHorizontalMerge }
    if (largestHorizontalMerge >= 0 && largeBlocks > 1) continue

    val merged = mergeHorizontally(group, ids.nextHorizontal())
    for (member in group) {
      idRemap[member.id] = merged.id
    }
    groups[scope] = mutableListOf(merged)
    blockCount -= group.size - 1
  }

  if (idRemap.isEmpty()) {
    return blockGraph
  }
  return BlockGraph(groups.values.flatten().map { remapBlockIds(it, idRemap) }.toSet())
}

private fun mergeHorizontally(group: List<Block>, id: String): Block {
  val first = group.first()
  return Block(
    id = id,
    initialLocation = first.initialLocation,
    finalLocation = first.finalLocation,
    locations = group.flatMapTo(mutableSetOf()) { it.locations },
    edges = group.flatMapTo(mutableSetOf()) { it.edges },
    predecessorIds = first.predecessorIds,
    successorIds = first.successorIds,
  )
}

/**
 * One vertical-merge pass, mirroring CPAchecker's `VerticalMergeDecomposition.mergeVertically`:
 * walks the (id-sorted) blocks once, and merges a block `X` with its successor `Y` into a new block
 * `MV<n>` whenever `X` has exactly one successor and `Y` has exactly one predecessor (necessarily
 * `X`). Each block takes part in at most one merge per pass, and the pass stops as soon as the
 * graph has at most [targetBlockCount] blocks. Predecessor/successor ids of every other block are
 * rewritten to the new ids (CPAchecker's `MergeIDTracker`), including the merged block's own ids
 * when `X` and `Y` also form a cycle.
 *
 * A block that is its own only successor is never merged with itself (CPAchecker would assert here;
 * such a block is unreachable in a valid block graph anyway).
 */
fun verticalMergePass(
  blockGraph: BlockGraph,
  targetBlockCount: Int = 0,
  ids: MergeIdGenerator = MergeIdGenerator.startingAfter(blockGraph),
): BlockGraph {
  val blocks = LinkedHashMap<String, Block>()
  blockGraph.blocks.sortedById().forEach { blocks[it.id] = it }
  val idRemap = mutableMapOf<String, String>()
  fun resolve(id: String): String {
    var current = id
    while (true) current = idRemap[current] ?: return current
  }

  val removed = mutableSetOf<String>()
  for (x in blockGraph.blocks.sortedById()) {
    if (x.id in removed) continue
    val successorId = x.successorIds.singleOrNull()?.let(::resolve) ?: continue
    if (successorId == x.id) continue
    val y = blocks[successorId] ?: continue
    if (y.predecessorIds.size != 1) continue

    val merged =
      Block(
        id = ids.nextVertical(),
        initialLocation = x.initialLocation,
        finalLocation = y.finalLocation,
        locations = x.locations + y.locations,
        edges = x.edges + y.edges,
        predecessorIds = x.predecessorIds,
        successorIds = y.successorIds,
      )
    blocks.remove(x.id)
    blocks.remove(y.id)
    blocks[merged.id] = merged
    removed += x.id
    removed += y.id
    idRemap[x.id] = merged.id
    idRemap[y.id] = merged.id
    if (blocks.size <= targetBlockCount) break
  }

  if (idRemap.isEmpty()) {
    return blockGraph
  }
  return BlockGraph(
    blocks.values
      .map { block ->
        block.copy(
          predecessorIds = block.predecessorIds.map(::resolve).toSet(),
          successorIds = block.successorIds.map(::resolve).toSet(),
        )
      }
      .toSet()
  )
}

/**
 * Repeats [horizontalMergePass] until the graph has at most [targetBlockCount] blocks or a pass
 * changes nothing - CPAchecker's `HorizontalMergeDecomposition.decompose`.
 */
fun horizontalMerge(
  blockGraph: BlockGraph,
  targetBlockCount: Int = 1,
  largestHorizontalMerge: Int = NO_MERGE_LIMIT,
  ids: MergeIdGenerator = MergeIdGenerator.startingAfter(blockGraph),
): BlockGraph =
  repeatUntilStable(blockGraph, targetBlockCount) {
    horizontalMergePass(it, targetBlockCount, largestHorizontalMerge, ids)
  }

/**
 * Repeats [verticalMergePass] until the graph has at most [targetBlockCount] blocks or a pass
 * changes nothing - CPAchecker's `VerticalMergeDecomposition.decompose`.
 */
fun verticalMerge(
  blockGraph: BlockGraph,
  targetBlockCount: Int = 1,
  ids: MergeIdGenerator = MergeIdGenerator.startingAfter(blockGraph),
): BlockGraph =
  repeatUntilStable(blockGraph, targetBlockCount) { verticalMergePass(it, targetBlockCount, ids) }

private fun repeatUntilStable(
  blockGraph: BlockGraph,
  targetBlockCount: Int,
  pass: (BlockGraph) -> BlockGraph,
): BlockGraph {
  var current = blockGraph
  while (current.blocks.size > targetBlockCount) {
    val next = pass(current)
    if (next.blocks.size == current.blocks.size) return next
    current = next
  }
  return current
}

private fun remapBlockIds(block: Block, idRemap: Map<String, String>): Block =
  block.copy(
    predecessorIds = block.predecessorIds.map { idRemap[it] ?: it }.toSet(),
    successorIds = block.successorIds.map { idRemap[it] ?: it }.toSet(),
  )

/**
 * Alternates [horizontalMergePass] and [verticalMergePass] exactly like CPAchecker's
 * `MergeBlockNodesDecomposition.decompose`: a horizontal pass, stop if at most [targetBlockCount]
 * blocks remain, a vertical pass, stop if the round did not reduce the block count, repeat.
 * [targetBlockCount] is not always reachable (a graph with real, non-rejoining branching cannot be
 * merged below the number of its divergent paths).
 */
fun mergeToTargetBlockCount(
  blockGraph: BlockGraph,
  targetBlockCount: Int,
  largestHorizontalMerge: Int = NO_MERGE_LIMIT,
): BlockGraph {
  val ids = MergeIdGenerator.startingAfter(blockGraph)
  var current = blockGraph
  while (current.blocks.size > targetBlockCount) {
    val sizeBefore = current.blocks.size
    current = horizontalMergePass(current, targetBlockCount, largestHorizontalMerge, ids)
    if (current.blocks.size <= targetBlockCount) break
    current = verticalMergePass(current, targetBlockCount, ids)
    if (current.blocks.size == sizeBefore) break
  }
  return current
}
