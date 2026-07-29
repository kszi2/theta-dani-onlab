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

import hu.bme.mit.theta.analysis.pred.PredPrec
import hu.bme.mit.theta.analysis.ptr.PtrPrec
import hu.bme.mit.theta.xcfa.analysis.XcfaPrec
import hu.bme.mit.theta.xcfa.model.XCFA
import hu.bme.mit.theta.xcfa.utils.collectAssumes

/**
 * A predicate precision seeded from every assume condition in [wholeProgram] - not narrowed to
 * whatever a single block's own edges happen to contain.
 *
 * Predicate-abstraction CEGAR only discovers predicates by refuting a counterexample; a block
 * checked in isolation ([extractBlockXcfa]) that has no property violation of its own to refute
 * against never refines its precision past whatever it started with. Most blocks in a decomposed
 * program are exactly that (a block boundary is a branch/join point per `blk_linear` - the
 * *branching* itself usually lives in a neighbouring block, not the one being summarized), so with
 * the checker's own default (empty) initial precision, [packPostcondition] would pack the trivial,
 * useless `True()` for almost every block - confirmed empirically while building this.
 *
 * Sharing one predicate pool, drawn from the whole original program's own assume/branch conditions,
 * up front across every block sidesteps the need for local refinement entirely: predicates like `x
 * < 10` discovered from a *different* block's branch are exactly what a block computing "what is
 * reachable at my exit" needs to state that precisely, even though its own edges never mention `x`
 * at all. This is a static, whole-program stand-in for what CPAchecker's DSS actually does
 * dynamically - refining a block's precision in response to a violation condition propagated back
 * from a successor (plan §2/§3) - and is intentionally cruder: no CEGAR-style refinement loop
 * between blocks yet, just one upfront, shared pool. Revisit once real violation-condition
 * propagation is wired in.
 */
fun globalAssumePredicatePrecision(wholeProgram: XCFA): XcfaPrec<PtrPrec<PredPrec>> =
  XcfaPrec(PtrPrec(PredPrec.of(wholeProgram.collectAssumes()), emptySet()))
