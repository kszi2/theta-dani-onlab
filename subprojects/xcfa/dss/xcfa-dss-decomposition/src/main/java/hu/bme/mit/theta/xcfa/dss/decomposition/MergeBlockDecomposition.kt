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

/**
 * `MERGE_DECOMPOSITION`, mirroring CPAchecker's confirmed `MergeBlockNodesDecomposition`: runs
 * [linearDecomposition] first, then alternates [horizontalMergePass]/[verticalMergePass]
 * ([mergeToTargetBlockCount]) until the block count drops to [targetBlockCount] blocks or no
 * further merge is possible.
 *
 * CPAchecker's own `@Option` documentation says [targetBlockCount] should converge to "the number
 * of functions in the program," but its actual wiring
 * (`DssDecompositionOptions.getConfiguredDecomposition`) passes a hardcoded `2` regardless of
 * function count - a documentation/implementation mismatch in the source, not something to
 * replicate here. [targetBlockCount] is a plain, caller-supplied number instead.
 *
 * Not ported: CPAchecker's option to refuse merging two blocks that both call/return the same
 * function. That option only makes sense for CPAchecker's CFA model, where a call is a
 * call/summary/return-edge triple spanning multiple nodes; XCFA represents a call as a single edge
 * carrying an `InvokeLabel`, with no equivalent structure to key the exclusion off of.
 */
class MergeBlockDecomposition(
  private val targetBlockCount: Int,
  private val maxHorizontalGroupSize: Int = Int.MAX_VALUE,
  private val linearDecomposition: DssBlockDecomposition = LinearBlockDecomposition(),
) : DssBlockDecomposition {

  override fun decompose(procedure: XcfaProcedure): BlockGraph =
    mergeToTargetBlockCount(
      linearDecomposition.decompose(procedure),
      targetBlockCount,
      maxHorizontalGroupSize,
    )
}
