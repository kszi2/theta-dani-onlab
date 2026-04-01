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

package hu.bme.mit.theta.xcfa.analysis.modular

import hu.bme.mit.theta.analysis.Cex
import hu.bme.mit.theta.analysis.Prec
import hu.bme.mit.theta.analysis.Trace
import hu.bme.mit.theta.analysis.algorithm.Proof
import hu.bme.mit.theta.analysis.algorithm.SafetyChecker
import hu.bme.mit.theta.analysis.algorithm.SafetyResult
import hu.bme.mit.theta.analysis.expr.ExprState
import hu.bme.mit.theta.analysis.ptr.PtrState
import hu.bme.mit.theta.common.logging.Logger
import hu.bme.mit.theta.common.logging.NullLogger
import hu.bme.mit.theta.core.model.ImmutableValuation
import hu.bme.mit.theta.core.model.Valuation
import hu.bme.mit.theta.xcfa.analysis.XcfaState
import hu.bme.mit.theta.xcfa.model.XCFA
import hu.bme.mit.theta.xcfa.utils.getMinimalCutsetsWithPartitionSizes
import hu.bme.mit.theta.xcfa.utils.mostBalancedCutset
import hu.bme.mit.theta.xcfa.utils.toDot
import hu.bme.mit.theta.xcfa.utils.splitByCutset
import hu.bme.mit.theta.xcfa.utils.withEntryAssignments
import java.io.File

/**
 * A modular safety checker that verifies a model using a list of safety checker factories.
 *
 * At the start of [check], the XCFA is split into two parts — *before* and *after* the most
 * balanced minimal edge cutset — using [getMinimalCutsetsWithPartitionSizes],
 * [mostBalancedCutset], and [splitByCutset]. When no cutset exists the original XCFA is used for
 * both halves. Each factory in [checkerFactories] is then invoked with the appropriate sub-XCFA to
 * produce a [SafetyChecker].
 *
 * @param W The witness type.
 * @param R The counterexample type.
 * @param P The precision type.
 * @param xcfa The XCFA to split before checking.
 * @param checkerFactories Lambdas that produce a [SafetyChecker] from an [XCFA].
 * @param logger Logger used to print XCFA structures before checking.
 */
class ModularChecker<W : Proof, R : Cex, P : Prec>(
  private val xcfa: XCFA,
  private val checkerFactories: List<(XCFA) -> SafetyChecker<W, R, P>>,
  private val logger: Logger = NullLogger.getInstance(),
) : SafetyChecker<W, R, P> {

  private val outDir: File =
    File("${xcfa.name}_${System.currentTimeMillis()}").also { it.mkdirs() }

  private fun writeDot(dot: String, filename: String) {
    val dotFile = File(outDir, filename)
    dotFile.writeText(dot)
    val pngFile = File(outDir, filename.removeSuffix(".dot") + ".png")
    ProcessBuilder("dot", "-Tpng", dotFile.absolutePath, "-o", pngFile.absolutePath)
      .redirectErrorStream(true)
      .start()
      .waitFor()
  }

  override fun check(prec: P?): SafetyResult<W, R> {
    writeDot(xcfa.toDot(), "original.dot")

    val cutset = xcfa.getMinimalCutsetsWithPartitionSizes().mostBalancedCutset()
    val (beforeXcfa, afterXcfa) =
      if (cutset != null) xcfa.splitByCutset(cutset) else Pair(xcfa, xcfa)

    writeDot(beforeXcfa.toDot(), "before.dot")
    writeDot(afterXcfa.toDot(), "after.dot")

    var iteration = 0
    while (true) {
      val result = checkerFactories.first()(beforeXcfa).check(prec)
      if (result.isSafe) {
        return result
      }
      //return result
      //}

      val cex = result.asUnsafe().cex
      var lastValuation: Valuation? = null
      if (cex is Trace<*, *>) {
        for ((i, state) in cex.states.withIndex()) {
          val valuation = when (state) {
            is XcfaState<*> -> unwrapValuation(state.sGlobal)
            is Valuation -> state
            else -> null
          }
          if (valuation != null && valuation.decls.isNotEmpty()) {
            logger.write(Logger.Level.INFO, "CEX state %d variable values:%n", i)
            for (decl in valuation.decls) {
              logger.write(Logger.Level.INFO, "  %s = %s%n", decl.name, valuation.eval(decl).orElse(null))
            }
            lastValuation = valuation
          }
        }
      }

      val filteredValuation = lastValuation?.let {
        ImmutableValuation.from(
          it.toMap().filterKeys { decl ->
            !decl.name.startsWith("__loc_") && !decl.name.startsWith("__edge_")
          }
        )
      }
      val nextXcfa =
        if (filteredValuation != null && filteredValuation.decls.isNotEmpty())
          afterXcfa.withEntryAssignments(filteredValuation)
        else afterXcfa

      val nextDot = nextXcfa.toDot()
      logger.write(Logger.Level.INFO, "%s%n", nextDot)
      writeDot(nextDot, "next_${iteration++}.dot")

      val result2 = checkerFactories.first()(nextXcfa).check(prec)

      if (result2.isUnsafe) {
        return result2 //todo stick the two traces together
      }
      //val proof = result2.proof



    }


    return checkerFactories.first()(xcfa).check(prec)
  }
}
private fun unwrapValuation(state: ExprState?): Valuation? =
  when (state) {
    is Valuation -> state
    is PtrState<*> -> unwrapValuation(state.innerState)
    else -> null
  }

/*while(true){
      val result = checkerFactories.first()(beforeXcfa).check(prec)
      //if(result.isSafe){
      //  return result
      //}
    }*
  }*/
