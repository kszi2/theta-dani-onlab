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

import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.xcfa.model.XCFA
import hu.bme.mit.theta.xcfa.utils.collectAssumes

/**
 * Every assume condition of [wholeProgram], used to seed the initial precision of every block.
 *
 * This has no counterpart in CPAchecker, whose blocks start with an empty precision and only gain
 * predicates through refinement (violation conditions make a block refine against them) and through
 * the precisions transmitted with postconditions. Theta's DSS does both of those too, so the pool
 * is optional (`--dss-global-predicate-pool`); it is a static, whole-program shortcut that lets a
 * block without anything to refute state its postcondition with predicates taken from the branches
 * of *other* blocks right away, which typically saves several rounds of violation conditions.
 */
fun globalAssumePredicates(wholeProgram: XCFA): Set<Expr<BoolType>> =
  wholeProgram.collectAssumes().toSet()

fun globalAssumePredicatePrecision(wholeProgram: XCFA) =
  predicatePrecision(globalAssumePredicates(wholeProgram))
