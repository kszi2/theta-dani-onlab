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

import hu.bme.mit.theta.analysis.algorithm.SafetyResult
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolExprs.False
import hu.bme.mit.theta.core.type.booltype.BoolExprs.Or
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.xcfa.analysis.proof.LocationInvariants
import hu.bme.mit.theta.xcfa.model.XcfaLocation

/**
 * The `packPost` half of the plan's §3 pack/unpack pair: turns a safe [runWorkerConfig] result into
 * a boolean formula summarizing every state the checker found reachable at [location] - the
 * postcondition a block hands to its successors.
 *
 * Only possible because [LocationInvariants] (the proof a safe [SafetyResult] carries) already maps
 * every location to the [hu.bme.mit.theta.analysis.expr.ExprState]s reached there, and - confirmed
 * by reading `getCegarChecker`'s own result-construction code - those states are already remapped
 * from whatever internal, renamed variables the checker used internally back onto the original
 * [hu.bme.mit.theta.core.decl.VarDecl]s declared on the checked procedure. So no unpacking beyond
 * `ExprState.toExpr()` and combining is needed here - the hard part already happened.
 *
 * [location] must be a location of the *checked* [hu.bme.mit.theta.xcfa.model.XCFA] itself - for a
 * [Block] extracted via [extractBlockXcfa], that means the cloned location from
 * [BlockXcfaExtraction.locationMapping], not the original.
 *
 * A location nothing reached at all packs to `False()` (the identity of `Or`) - not missing/null:
 * "nothing reaches here" is exactly as meaningful a postcondition as any other, and should
 * propagate as such (a successor fed `False()` as a precondition correctly finds nothing reachable
 * either, rather than the caller having to special-case "no postcondition" separately). Individual
 * states whose own `toExpr()` is already `False()` - predicate abstraction represents an
 * infeasible-but-structurally-present path this way, confirmed empirically: a location whose only
 * incoming edge turned out unsatisfiable still gets an (unsatisfiable) entry in
 * [LocationInvariants] rather than being omitted - are filtered out before combining, so packing a
 * genuinely unreachable location returns the bare `False()` rather than `Or(False())`/`Or(False(),
 * False(), ...)` clutter that would otherwise accumulate as postconditions chain through more
 * blocks.
 */
fun packPostcondition(
  result: SafetyResult<LocationInvariants, *>,
  location: XcfaLocation,
): Expr<BoolType> {
  check(result.isSafe) { "Can only pack a postcondition out of a safe result, but got: $result" }
  val states = result.asSafe().proof.getPartitions()[location].orEmpty()
  val reachableExprs = states.map { it.toExpr() }.filterNot { it == False() }
  return if (reachableExprs.isEmpty()) False() else Or(reachableExprs)
}
