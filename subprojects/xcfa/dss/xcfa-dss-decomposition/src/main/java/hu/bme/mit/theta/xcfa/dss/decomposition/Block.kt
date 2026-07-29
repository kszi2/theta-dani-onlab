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

/**
 * A coherent sub-graph of a procedure's control-flow graph, with a single designated entry
 * ([initialLocation]) and exit ([finalLocation]).
 *
 * A [Block] does not own or wrap a copy of the underlying [XcfaLocation]/[XcfaEdge] objects; it
 * references the same instances that appear in the (single, un-duplicated) procedure CFG. Multiple
 * blocks may therefore share a location at their boundary, e.g. a join point is simultaneously the
 * [finalLocation] of every block that merges into it and the [initialLocation] of every block that
 * starts from it.
 *
 * [predecessorIds] and [successorIds] reference sibling [Block]s by [id] within the same
 * [BlockGraph], mirroring CPAchecker's `BlockNode`.
 */
data class Block(
  val id: String,
  val initialLocation: XcfaLocation,
  val finalLocation: XcfaLocation,
  val locations: Set<XcfaLocation>,
  val edges: Set<XcfaEdge>,
  val predecessorIds: Set<String> = emptySet(),
  val successorIds: Set<String> = emptySet(),
  /**
   * Where a worker analyzing this block should place its abstraction point, distinct from
   * [finalLocation] whenever [BlockGraphInstrumentation] was able to give this block a dedicated
   * one (see there for why the two need to differ at all). Defaults to [finalLocation] - i.e. "no
   * dedicated abstraction point yet" - for blocks fresh out of a [DssBlockDecomposition] that
   * hasn't been instrumented.
   */
  val violationConditionLocation: XcfaLocation = finalLocation,
) {

  val isRoot: Boolean
    get() = predecessorIds.isEmpty()

  /** Whether this block has a dedicated abstraction point, distinct from [finalLocation]. */
  val isAbstractionPossible: Boolean
    get() = violationConditionLocation != finalLocation
}
