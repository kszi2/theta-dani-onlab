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
package hu.bme.mit.theta.xcfa.cli.checkers

import hu.bme.mit.theta.analysis.EmptyCex
import hu.bme.mit.theta.analysis.algorithm.EmptyProof
import hu.bme.mit.theta.analysis.algorithm.SafetyChecker
import hu.bme.mit.theta.analysis.algorithm.SafetyResult
import hu.bme.mit.theta.common.logging.Logger
import hu.bme.mit.theta.frontend.ParseContext
import hu.bme.mit.theta.graphsolver.patterns.constraints.MCM
import hu.bme.mit.theta.xcfa.analysis.XcfaPrec
import hu.bme.mit.theta.xcfa.cli.params.DssConfig
import hu.bme.mit.theta.xcfa.cli.params.DssDecomposition
import hu.bme.mit.theta.xcfa.cli.params.DssExecutor
import hu.bme.mit.theta.xcfa.cli.params.XcfaConfig
import hu.bme.mit.theta.xcfa.cli.params.defaultPredicateCegarConfig
import hu.bme.mit.theta.xcfa.dss.actor.DssResult
import hu.bme.mit.theta.xcfa.dss.actor.PredicateBlockBehavior
import hu.bme.mit.theta.xcfa.dss.actor.runDssActors
import hu.bme.mit.theta.xcfa.dss.actor.runDssActorsSequentially
import hu.bme.mit.theta.xcfa.dss.analysis.XcfaChecker
import hu.bme.mit.theta.xcfa.dss.decomposition.Block
import hu.bme.mit.theta.xcfa.dss.decomposition.BlockGraph
import hu.bme.mit.theta.xcfa.dss.decomposition.LinearBlockDecomposition
import hu.bme.mit.theta.xcfa.dss.decomposition.MergeBlockDecomposition
import hu.bme.mit.theta.xcfa.model.XCFA

/**
 * `--backend DSS`: runs Distributed Summary Synthesis instead of an ordinary, monolithic checker -
 * see `doc/DSS.md` for the full design. Wrapped as an ordinary [SafetyChecker] lambda (mirroring
 * `getPortfolioChecker`, the closest existing precedent for "the algorithm isn't a single
 * `SafetyChecker.check()` call") so it flows through exactly the same result-reporting/witness
 * pipeline every other backend already uses (`ExecuteConfig.kt`'s `backend()`).
 *
 * DSS only supports a single procedure so far (`doc/DSS.md`'s Known Limitations - block
 * decomposition happens per-procedure, not yet across procedure boundaries); a multi-procedure
 * input fails fast with a clear message rather than silently checking only one procedure.
 *
 * The [checkerFactory] every block's [PredicateBlockBehavior] forwards to is always
 * [getCegarChecker] with [defaultPredicateCegarConfig] - the same "thin orchestration layer" every
 * DSS test in `xcfa-dss-actor` builds by hand, just assembled here instead. There is currently no
 * `--dss-worker-config` flag to substitute a different worker configuration (see the plan's open
 * questions on why this is more involved than it first looks); every DSS run uses the one default
 * config.
 *
 * DSS produces no concrete counterexample trace ([EmptyCex]) and no invariant proof beyond a
 * trivial one ([EmptyProof]) for either verdict - matching the paper's own stated "Verification
 * Witnesses" limitation (DSS cannot yet produce violation witnesses), and CPAchecker's own
 * implementation, which does not restore one either; it just flips the verdict.
 */
fun getDssChecker(
  xcfa: XCFA,
  mcm: MCM,
  config: XcfaConfig<*, *>,
  parseContext: ParseContext,
  logger: Logger,
): SafetyChecker<EmptyProof, EmptyCex, XcfaPrec<*>> = SafetyChecker { _ ->
  val dssConfig = config.backendConfig.specConfig as DssConfig
  val procedure =
    xcfa.procedures.singleOrNull()
      ?: error(
        "DSS only supports single-procedure programs so far (found ${xcfa.procedures.size} " +
          "procedures) - see doc/DSS.md's Known Limitations."
      )

  val blockGraph: BlockGraph =
    when (dssConfig.decomposition) {
      DssDecomposition.LINEAR -> LinearBlockDecomposition().decompose(procedure)
      DssDecomposition.MERGE ->
        MergeBlockDecomposition(targetBlockCount = dssConfig.targetBlockCount).decompose(procedure)
      DssDecomposition.NONE ->
        // NO_DECOMPOSITION: the whole procedure as a single block, mirroring CPAchecker's
        // SingleBlockDecomposition (finalLocation = the procedure's own dead-end/exit location,
        // not its entry - a single-block, no-successor root never actually needs its postcondition
        // consumed, but violationConditionLocation still defaults to finalLocation, and a wrong
        // value there would silently pack a meaningless postcondition if this were ever inspected).
        BlockGraph(
          setOf(
            Block(
              id = "whole",
              initialLocation = procedure.initLoc,
              finalLocation =
                procedure.locs.singleOrNull { it.outgoingEdges.isEmpty() }
                  ?: procedure.finalLoc.orElseThrow {
                    IllegalStateException(
                      "DSS --dss-decomposition NONE requires the procedure to have exactly one " +
                        "dead-end location or a designated final location"
                    )
                  },
              locations = procedure.locs,
              edges = procedure.edges,
            )
          )
        )
    }

  val checkerFactory: (XCFA) -> XcfaChecker = { blockXcfa ->
    getCegarChecker(blockXcfa, mcm, parseContext, defaultPredicateCegarConfig(), logger)
  }
  val behaviorFor = { block: Block ->
    PredicateBlockBehavior(xcfa, procedure, block, checkerFactory)
  }

  val dssResult =
    when (dssConfig.executor) {
      DssExecutor.CONCURRENT -> runDssActors(blockGraph, behaviorFor = behaviorFor)
      DssExecutor.SEQUENTIAL -> runDssActorsSequentially(blockGraph, behaviorFor = behaviorFor)
    }

  when (dssResult) {
    DssResult.SAFE -> SafetyResult.safe(EmptyProof.getInstance())
    DssResult.UNSAFE -> SafetyResult.unsafe(EmptyCex.getInstance(), EmptyProof.getInstance())
  }
}
