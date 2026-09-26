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
package hu.bme.mit.theta.analysis.algorithm.frame.car;

import static hu.bme.mit.theta.core.type.booltype.BoolExprs.False;
import static hu.bme.mit.theta.core.type.booltype.BoolExprs.True;

import com.google.common.base.Preconditions;
import hu.bme.mit.theta.analysis.Trace;
import hu.bme.mit.theta.analysis.algorithm.SafetyChecker;
import hu.bme.mit.theta.analysis.algorithm.SafetyResult;
import hu.bme.mit.theta.analysis.algorithm.bounded.MonolithicExpr;
import hu.bme.mit.theta.analysis.expl.ExplState;
import hu.bme.mit.theta.analysis.expr.ExprAction;
import hu.bme.mit.theta.analysis.expr.refinement.ExprTraceChecker;
import hu.bme.mit.theta.analysis.expr.refinement.ExprTraceStatus;
import hu.bme.mit.theta.analysis.expr.refinement.ItpRefutation;
import hu.bme.mit.theta.analysis.pred.ExprSplitters;
import hu.bme.mit.theta.analysis.pred.ExprSplitters.ExprSplitter;
import hu.bme.mit.theta.analysis.pred.PredPrec;
import hu.bme.mit.theta.analysis.pred.PredState;
import hu.bme.mit.theta.analysis.unit.UnitPrec;
import hu.bme.mit.theta.common.logging.Logger;
import hu.bme.mit.theta.core.utils.ExprUtils;
import hu.bme.mit.theta.solver.SolverFactory;
import java.util.stream.Collectors;
import kotlin.jvm.functions.Function1;

public class CarCegarChecker
        implements SafetyChecker<PredState, Trace<ExplState, ExprAction>, UnitPrec> {
    private MonolithicExpr monolithicExpr;
    private final SolverFactory solverFactory;
    private final CarOptimizations optimizations;
    private final Logger logger;
    private final Function1<MonolithicExpr, ExprTraceChecker<ItpRefutation>> traceCheckerFactory;
    private final ExprSplitter predSplitter;

    public CarCegarChecker(
            MonolithicExpr monolithicExpr,
            SolverFactory solverFactory,
            Function1<MonolithicExpr, ExprTraceChecker<ItpRefutation>> traceCheckerFactory,
            CarOptimizations optimizations,
            Logger logger) {
        this(
                monolithicExpr,
                solverFactory,
                traceCheckerFactory,
                optimizations,
                ExprSplitters.whole(),
                logger);
    }

    public CarCegarChecker(
            MonolithicExpr monolithicExpr,
            SolverFactory solverFactory,
            Function1<MonolithicExpr, ExprTraceChecker<ItpRefutation>> traceCheckerFactory,
            CarOptimizations optimizations,
            ExprSplitter predSplitter,
            Logger logger) {
        this.monolithicExpr = monolithicExpr;
        this.solverFactory = solverFactory;
        this.optimizations = optimizations;
        this.logger = logger;
        this.traceCheckerFactory = traceCheckerFactory;
        this.predSplitter = predSplitter;
    }

    @Override
    public SafetyResult<PredState, Trace<ExplState, ExprAction>> check(UnitPrec prec) {
        AbstractHelper helper = new AbstractHelper(traceCheckerFactory, logger);
        MonolithicExpr abstractModel = helper.createPrec(monolithicExpr);
        var checker = new CarChecker<>(abstractModel, solverFactory, optimizations, logger);
        while (true) {
            logger.write(
                    Logger.Level.SUBSTEP,
                    "Current prec: %d predicates\n",
                    helper.currentPrec.getPreds().size());
            logger.write(Logger.Level.VERBOSE, "Current prec: %s\n", helper.currentPrec);
            var result = checker.check();
            if (result.isSafe()) {
                logger.write(Logger.Level.MAINSTEP, "Model is safe, stopping CEGAR");
                return SafetyResult.safe(result.getProof());
            } else {
                Preconditions.checkState(result.isUnsafe());
                logger.write(
                        Logger.Level.VERBOSE, "\tFound trace: %s\n", result.asUnsafe().getCex());

                final ExprTraceStatus<ItpRefutation> concretizationResult =
                        helper.getConcretisationResult(result.asUnsafe().getCex());
                if (concretizationResult.isFeasible()) {
                    logger.write(Logger.Level.MAINSTEP, "Model is unsafe, stopping CEGAR\n");
                    return result; // todo add concrete result
                } else {
                    final var ref = concretizationResult.asInfeasible().getRefutation();
                    checker.prune(ref.getPruneIndex(), false);
                    // every non-trivial interpolant of the refutation (a binary refutation has
                    // only one, a sequence refutation can have several)
                    final var newPreds =
                            ref.stream()
                                    .flatMap(itp -> predSplitter.apply(itp).stream())
                                    .filter(itp -> !itp.equals(True()) && !itp.equals(False()))
                                    .collect(Collectors.toSet());
                    final int predCountBefore = helper.currentPrec.getPreds().size();
                    helper.currentPrec = helper.currentPrec.join(PredPrec.of(newPreds));
                    final int predCountAfter = helper.currentPrec.getPreds().size();
                    logger.write(
                            Logger.Level.INFO,
                            "Refinement predicates (%d nodes) added %d new predicates (now %d)%n",
                            newPreds.stream().mapToInt(ExprUtils::nodeCountSize).sum(),
                            predCountAfter - predCountBefore,
                            predCountAfter);
                    logger.write(Logger.Level.VERBOSE, "Refinement predicates %s%n", newPreds);
                   /* if (predCountAfter == predCountBefore) {
                        // the same abstraction would be rebuilt and the same spurious
                        // counterexample found again, so CEGAR would never terminate
                        throw new IllegalStateException(
                                String.format(
                                        "CEGAR refinement made no progress: interpolants %s are"
                                                + " already in the precision",
                                        newPreds));
                    }*/ //todo check what happens in this case, is this a problem?
                    final var abstractMonolithicExpr =
                            helper.createAbstract(monolithicExpr, helper.currentPrec);
                    checker.setMonolithicExpr(abstractMonolithicExpr);
                }
            }
        }
    }
}
