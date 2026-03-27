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

package hu.bme.mit.theta.xcfa.analysis

import hu.bme.mit.theta.analysis.Cex
import hu.bme.mit.theta.analysis.Prec
import hu.bme.mit.theta.analysis.algorithm.Proof
import hu.bme.mit.theta.analysis.algorithm.SafetyChecker
import hu.bme.mit.theta.analysis.algorithm.SafetyResult

/**
 * A modular safety checker that verifies a model using a list of safety checkers.
 *
 * @param W The witness type.
 * @param R The counterexample type.
 * @param P The precision type.
 * @param checkers The list of safety checkers to use.
 */
class ModularChecker<W : Proof, R : Cex, P : Prec>(
  private val checkers: List<SafetyChecker<W, R, P>>,
) : SafetyChecker<W, R, P> {

  override fun check(prec: P?): SafetyResult<W, R> {

    return checkers.first().check(prec)
  }
}