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

import hu.bme.mit.theta.analysis.EmptyCex
import hu.bme.mit.theta.analysis.Trace
import hu.bme.mit.theta.analysis.algorithm.EmptyProof
import hu.bme.mit.theta.analysis.algorithm.SafetyChecker
import hu.bme.mit.theta.analysis.algorithm.SafetyResult
import hu.bme.mit.theta.analysis.algorithm.arg.ARG
import hu.bme.mit.theta.analysis.algorithm.bounded.buildBMC
import hu.bme.mit.theta.analysis.algorithm.cegar.ArgAbstractor
import hu.bme.mit.theta.analysis.algorithm.cegar.ArgCegarChecker
import hu.bme.mit.theta.analysis.algorithm.cegar.ArgRefiner
import hu.bme.mit.theta.analysis.algorithm.cegar.abstractor.StopCriterions
import hu.bme.mit.theta.analysis.expl.ExplOrd
import hu.bme.mit.theta.analysis.expl.ExplPrec
import hu.bme.mit.theta.analysis.expl.ExplState
import hu.bme.mit.theta.analysis.expl.ItpRefToExplPrec
import hu.bme.mit.theta.analysis.expr.ExprAction
import hu.bme.mit.theta.analysis.expr.refinement.ExprTraceBwBinItpChecker
import hu.bme.mit.theta.analysis.expr.refinement.ItpRefutation
import hu.bme.mit.theta.analysis.expr.refinement.PruneStrategy
import hu.bme.mit.theta.analysis.pred.PredState
import hu.bme.mit.theta.analysis.ptr.ItpRefToPtrPrec
import hu.bme.mit.theta.analysis.ptr.PtrPrec
import hu.bme.mit.theta.analysis.ptr.PtrState
import hu.bme.mit.theta.analysis.ptr.getPtrPartialOrd
import hu.bme.mit.theta.analysis.unit.UnitPrec
import hu.bme.mit.theta.c2xcfa.getXcfaFromC
import hu.bme.mit.theta.common.logging.ConsoleLogger
import hu.bme.mit.theta.common.logging.Logger
import hu.bme.mit.theta.common.logging.NullLogger
import hu.bme.mit.theta.core.type.booltype.BoolExprs
import hu.bme.mit.theta.frontend.ParseContext
import hu.bme.mit.theta.solver.z3legacy.Z3LegacySolverFactory
import hu.bme.mit.theta.xcfa.ErrorDetection
import hu.bme.mit.theta.xcfa.XcfaProperty
import hu.bme.mit.theta.xcfa.analysis.modular.ModularChecker
import hu.bme.mit.theta.xcfa.analysis.monolithic.XcfaSingleThreadToMonolithicAdapter
import hu.bme.mit.theta.xcfa.analysis.por.XcfaDporLts
import hu.bme.mit.theta.xcfa.model.XCFA
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ModularCheckerTest {

  companion object {

    private val property = XcfaProperty(ErrorDetection.ERROR_LOCATION)

    @JvmStatic
    fun data(): Collection<Array<Any>> {
      return listOf(
//        arrayOf("/00assignment.c", SafetyResult<*, *>::isUnsafe),
//        arrayOf("/01function.c", SafetyResult<*, *>::isUnsafe),
//        arrayOf("/02functionparam.c", SafetyResult<*, *>::isSafe),
//        arrayOf("/03nondetfunction.c", SafetyResult<*, *>::isUnsafe),
        //arrayOf("/04multithread.c", SafetyResult<*, *>::isUnsafe),
        // chain-of-additions: a=1, b=a+2=3, c=b+2=5
        arrayOf("/11addchain_unsafe.c", SafetyResult<*, *>::isUnsafe),
        arrayOf("/12addchain_safe.c", SafetyResult<*, *>::isSafe),
        // function-call sequence: add_two(3)=5
//        arrayOf("/13funcseq_unsafe.c", SafetyResult<*, *>::isUnsafe),
//        arrayOf("/14funcseq_safe.c", SafetyResult<*, *>::isSafe),
//        // nondeterministic: x can equal 42
//        arrayOf("/15nondet_unsafe.c", SafetyResult<*, *>::isUnsafe),
      )
    }

    // Multithread not supported by the single-thread monolithic adapter
    @JvmStatic
    fun singleThreadData(): Collection<Array<Any>> {
      return listOf(
//        arrayOf("/00assignment.c", SafetyResult<*, *>::isUnsafe),
//        arrayOf("/01function.c", SafetyResult<*, *>::isUnsafe),
//        arrayOf("/02functionparam.c", SafetyResult<*, *>::isSafe),
//        //arrayOf("/03nondetfunction.c", SafetyResult<*, *>::isUnsafe),
//        // chain-of-additions: a=1, b=a+2=3, c=b+2=5
//        arrayOf("/11addchain_unsafe.c", SafetyResult<*, *>::isUnsafe),
//        arrayOf("/12addchain_safe.c", SafetyResult<*, *>::isSafe),
//        // function-call sequence: add_two(3)=5
//        arrayOf("/13funcseq_unsafe.c", SafetyResult<*, *>::isUnsafe),
//        arrayOf("/14funcseq_safe.c", SafetyResult<*, *>::isSafe),
      )
    }
  }

  private fun buildCegarChecker(
    xcfa: XCFA
  ): SafetyChecker<
    ARG<XcfaState<PtrState<ExplState>>, XcfaAction>,
    hu.bme.mit.theta.analysis.Trace<XcfaState<PtrState<ExplState>>, XcfaAction>,
    XcfaPrec<PtrPrec<ExplPrec>>,
  > {
    val analysis =
      ExplXcfaAnalysis(
        xcfa,
        Z3LegacySolverFactory.getInstance().createSolver(),
        1,
        XcfaDporLts.getPartialOrder(getPartialOrder(ExplOrd.getInstance().getPtrPartialOrd())),
        false,
      )
    val lts = XcfaDporLts(xcfa)
    val abstractor =
      getXcfaAbstractor(
        analysis,
        lts.waitlist,
        StopCriterions.firstCex<XcfaState<PtrState<ExplState>>, XcfaAction>(),
        ConsoleLogger(Logger.Level.DETAIL),
        lts,
        getXcfaErrorDetector(property.verifiedProperty),
      )
        as ArgAbstractor<XcfaState<PtrState<ExplState>>, XcfaAction, XcfaPrec<PtrPrec<ExplPrec>>>
    val refiner =
      XcfaSingleExprTraceRefiner.create(
        ExprTraceBwBinItpChecker.create(
          BoolExprs.True(),
          BoolExprs.True(),
          Z3LegacySolverFactory.getInstance().createItpSolver(),
        ),
        XcfaPrecRefiner<XcfaState<PtrState<ExplState>>, ExplPrec, ItpRefutation>(
          ItpRefToPtrPrec(ItpRefToExplPrec())
        ),
        PruneStrategy.FULL,
        ConsoleLogger(Logger.Level.DETAIL),
      ) as ArgRefiner<XcfaState<PtrState<ExplState>>, XcfaAction, XcfaPrec<PtrPrec<ExplPrec>>>
    return ArgCegarChecker.create(abstractor, refiner)
  }



  @ParameterizedTest
  @MethodSource("data")
  fun testModularCheckerExpl(filepath: String, verdict: (SafetyResult<*, *>) -> Boolean) {
    println("Testing ModularChecker on $filepath...")
    val xcfa =
      getXcfaFromC(
          javaClass.getResourceAsStream(filepath)!!,
          ParseContext(),
          false,
          property,
          NullLogger.getInstance(),
        )
        .first

    val checker = ModularChecker(xcfa, listOf(::buildCegarChecker), ConsoleLogger(Logger.Level.INFO))
    Assertions.assertTrue(verdict(checker.check(XcfaPrec(PtrPrec(ExplPrec.empty(), emptySet())))))
  }

  @Disabled("singleThreadData is currently empty")
  @ParameterizedTest
  @MethodSource("singleThreadData")
  fun testModularCheckerBounded(filepath: String, verdict: (SafetyResult<*, *>) -> Boolean) {
    println("Testing ModularChecker with BoundedChecker (BMC) on $filepath...")
    val xcfa =
      getXcfaFromC(
          javaClass.getResourceAsStream(filepath)!!,
          ParseContext(),
          false,
          property,
          NullLogger.getInstance(),
        )
        .first

    val checker = ModularChecker(xcfa, listOf { xcfaArg ->
      val monolithicExpr = XcfaSingleThreadToMonolithicAdapter(xcfaArg, property, ParseContext()).monolithicExpr
      buildBMC(monolithicExpr, Z3LegacySolverFactory.getInstance().createSolver(), NullLogger.getInstance())
    }, ConsoleLogger(Logger.Level.INFO))
    Assertions.assertTrue(verdict(checker.check(UnitPrec.getInstance())))
  }
}
