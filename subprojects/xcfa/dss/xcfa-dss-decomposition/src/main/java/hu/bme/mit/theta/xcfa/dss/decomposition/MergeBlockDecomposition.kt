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

import hu.bme.mit.theta.xcfa.model.XcfaProcedure

/** CPAchecker's hardcoded merge target (`DssDecompositionOptions.getConfiguredDecomposition`). */
const val DEFAULT_TARGET_BLOCK_COUNT = 2

/**
 * `MERGE_DECOMPOSITION`, mirroring CPAchecker's `MergeBlockNodesDecomposition`: runs
 * [linearDecomposition] first, then alternates horizontal/vertical merge passes
 * ([mergeToTargetBlockCount]) until the block count drops to [targetBlockCount] or no further merge
 * is possible.
 *
 * CPAchecker's `@Option` documentation says the target should converge to "the number of functions
 * in the program", but its actual wiring passes a hardcoded `2`; [DEFAULT_TARGET_BLOCK_COUNT] is
 * that value, and here it is a parameter instead.
 *
 * [largestHorizontalMerge] is CPAchecker's `largestHorizontalMerge` option (see
 * [horizontalMergePass]), and [allowSingleBlockDecomposition] is its
 * `allowSingleBlockDecompositionWhenMerging`: with a target of at most one block, the whole
 * procedure becomes a single block without running the linear decomposition at all.
 *
 * Not ported: CPAchecker's `mergeFunctionCalls` flag, which refuses merges that would put two
 * calls/returns of the same function into one block. That only makes sense for CPAchecker's CFA,
 * where a call is a call/summary/return-edge triple spanning several nodes; DSS in Theta works on a
 * single XCFA procedure, where a call is a single edge.
 */
class MergeBlockDecomposition(
  private val targetBlockCount: Int = DEFAULT_TARGET_BLOCK_COUNT,
  private val largestHorizontalMerge: Int = NO_MERGE_LIMIT,
  private val allowSingleBlockDecomposition: Boolean = false,
  private val linearDecomposition: DssBlockDecomposition = LinearBlockDecomposition(),
) : DssBlockDecomposition {

  override fun decompose(procedure: XcfaProcedure): BlockGraph {
    if (targetBlockCount <= 1 && allowSingleBlockDecomposition) {
      return SingleBlockDecomposition.decompose(procedure)
    }
    return mergeToTargetBlockCount(
      linearDecomposition.decompose(procedure),
      targetBlockCount,
      largestHorizontalMerge,
    )
  }
}
