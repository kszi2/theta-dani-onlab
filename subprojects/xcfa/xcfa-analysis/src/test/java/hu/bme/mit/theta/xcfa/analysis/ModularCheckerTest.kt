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

import hu.bme.mit.theta.analysis.algorithm.SafetyResult
import hu.bme.mit.theta.analysis.algorithm.cegar.ArgAbstractor
import hu.bme.mit.theta.analysis.algorithm.cegar.ArgRefiner
import hu.bme.mit.theta.analysis.algorithm.cegar.abstractor.StopCriterions
import hu.bme.mit.theta.analysis.algorithm.modular.ModularChecker
import hu.bme.mit.theta.analysis.expl.ExplOrd
import hu.bme.mit.theta.analysis.expl.ExplPrec
import hu.bme.mit.theta.analysis.expl.ExplState
import hu.bme.mit.theta.analysis.expl.ItpRefToExplPrec
import hu.bme.mit.theta.analysis.expr.refinement.ExprTraceBwBinItpChecker
import hu.bme.mit.theta.analysis.expr.refinement.ItpRefutation
import hu.bme.mit.theta.analysis.expr.refinement.PruneStrategy
import hu.bme.mit.theta.analysis.ptr.ItpRefToPtrPrec
import hu.bme.mit.theta.analysis.ptr.PtrPrec
import hu.bme.mit.theta.analysis.ptr.PtrState
import hu.bme.mit.theta.analysis.ptr.getPtrPartialOrd
import hu.bme.mit.theta.c2xcfa.getXcfaFromC
import hu.bme.mit.theta.common.logging.ConsoleLogger
import hu.bme.mit.theta.common.logging.Logger
import hu.bme.mit.theta.common.logging.NullLogger
import hu.bme.mit.theta.core.type.booltype.BoolExprs
import hu.bme.mit.theta.frontend.ParseContext
import hu.bme.mit.theta.solver.z3legacy.Z3LegacySolverFactory
import hu.bme.mit.theta.xcfa.ErrorDetection
import hu.bme.mit.theta.xcfa.XcfaProperty
import hu.bme.mit.theta.xcfa.analysis.por.XcfaDporLts
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ModularCheckerTest {

  companion object {

    private val property = XcfaProperty(ErrorDetection.ERROR_LOCATION)

    @JvmStatic
    fun data(): Collection<Array<Any>> {
      return listOf(
        arrayOf("/00assignment.c", SafetyResult<*, *>::isUnsafe),
        arrayOf("/01function.c", SafetyResult<*, *>::isUnsafe),
        arrayOf("/02functionparam.c", SafetyResult<*, *>::isSafe),
        arrayOf("/03nondetfunction.c", SafetyResult<*, *>::isUnsafe),
        arrayOf("/04multithread.c", SafetyResult<*, *>::isUnsafe),
      )
    }
  }

  @ParameterizedTest
  @MethodSource("data")
  fun testModularCheckerExpl(filepath: String, verdict: (SafetyResult<*, *>) -> Boolean) {
    println("Testing ModularChecker on $filepath...")
    val stream = javaClass.getResourceAsStream(filepath)
    val xcfa =
      getXcfaFromC(stream!!, ParseContext(), false, property, NullLogger.getInstance()).first
    val analysis =
      ExplXcfaAnalysis(
        xcfa,
        Z3LegacySolverFactory.getInstance().createSolver(),
        1,
        XcfaDporLts.getPartialOrder(getPartialOrder(ExplOrd.getInstance().getPtrPartialOrd())),
        false,
      )

    val lts = XcfaDporLts(xcfa)

    val errorDetector = getXcfaErrorDetector(property.verifiedProperty)
    val abstractor =
      getXcfaAbstractor(
        analysis,
        lts.waitlist,
        StopCriterions.firstCex<XcfaState<PtrState<ExplState>>, XcfaAction>(),
        ConsoleLogger(Logger.Level.DETAIL),
        lts,
        errorDetector,
      )
        as ArgAbstractor<XcfaState<PtrState<ExplState>>, XcfaAction, XcfaPrec<PtrPrec<ExplPrec>>>

    val precRefiner =
      XcfaPrecRefiner<XcfaState<PtrState<ExplState>>, ExplPrec, ItpRefutation>(
        ItpRefToPtrPrec(ItpRefToExplPrec())
      )

    val refiner =
      XcfaSingleExprTraceRefiner.create(
        ExprTraceBwBinItpChecker.create(
          BoolExprs.True(),
          BoolExprs.True(),
          Z3LegacySolverFactory.getInstance().createItpSolver(),
        ),
        precRefiner,
        PruneStrategy.FULL,
        ConsoleLogger(Logger.Level.DETAIL),
      ) as ArgRefiner<XcfaState<PtrState<ExplState>>, XcfaAction, XcfaPrec<PtrPrec<ExplPrec>>>
    val checker = ModularChecker(xcfa, abstractor,refiner)

    val safetyResult = checker.check(XcfaPrec(PtrPrec(ExplPrec.empty(), emptySet())))

    Assertions.assertTrue(verdict(safetyResult))
  }
}
