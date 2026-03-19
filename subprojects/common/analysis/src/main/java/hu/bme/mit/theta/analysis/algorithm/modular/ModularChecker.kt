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

package hu.bme.mit.theta.analysis.algorithm.modular

import hu.bme.mit.theta.analysis.Action
import hu.bme.mit.theta.analysis.Prec
import hu.bme.mit.theta.analysis.State
import hu.bme.mit.theta.analysis.Trace
import hu.bme.mit.theta.analysis.algorithm.SafetyChecker
import hu.bme.mit.theta.analysis.algorithm.SafetyResult
import hu.bme.mit.theta.analysis.algorithm.arg.ARG
import hu.bme.mit.theta.analysis.algorithm.cegar.ArgAbstractor
import hu.bme.mit.theta.analysis.algorithm.cegar.ArgCegarChecker
import hu.bme.mit.theta.analysis.algorithm.cegar.ArgRefiner
import hu.bme.mit.theta.common.logging.Logger
import hu.bme.mit.theta.common.logging.NullLogger

/**
 * A modular safety checker that verifies a model by checking it with a CEGAR loop.
 *
 * @param M The model type (e.g. XCFA) being verified.
 * @param S The state type.
 * @param A The action type.
 * @param P The precision type.
 * @param model The model to be verified.
 * @param abstractor The abstractor for the CEGAR loop.
 * @param refiner The refiner for the CEGAR loop.
 * @param logger The logger for logging.
 */
class ModularChecker<M, S : State, A : Action, P : Prec>
@JvmOverloads
constructor(
  private val model: M,
  private val abstractor: ArgAbstractor<S, A, P>,
  private val refiner: ArgRefiner<S, A, P>,
  private val logger: Logger = NullLogger.getInstance(),
) : SafetyChecker<ARG<S, A>, Trace<S, A>, P> {

  private val cegarChecker = ArgCegarChecker.create(abstractor, refiner, logger)

  override fun check(prec: P?): SafetyResult<ARG<S, A>, Trace<S, A>> {
    logger.write(Logger.Level.MAINSTEP, "Starting modular checking on $model\n")
    return cegarChecker.check(prec)
  }
}
