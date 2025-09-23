/*
 *  Copyright 2025 Budapest University of Technology and Economics
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
package hu.bme.mit.theta.analysis.algorithm.car;

import com.google.common.base.Preconditions;
import hu.bme.mit.theta.analysis.Action;
import hu.bme.mit.theta.analysis.Trace;
import hu.bme.mit.theta.analysis.algorithm.Checker;
import hu.bme.mit.theta.analysis.algorithm.EmptyProof;
import hu.bme.mit.theta.analysis.algorithm.SafetyChecker;
import hu.bme.mit.theta.analysis.algorithm.SafetyResult;
import hu.bme.mit.theta.analysis.algorithm.bounded.AbstractMonolithicExprKt;
import hu.bme.mit.theta.analysis.algorithm.bounded.MonolithicExpr;
import hu.bme.mit.theta.analysis.algorithm.bounded.MonolithicExprKt;
import hu.bme.mit.theta.analysis.algorithm.ic3.Ic3Checker;
import hu.bme.mit.theta.analysis.expr.ExprAction;
import hu.bme.mit.theta.analysis.expr.ExprState;
import hu.bme.mit.theta.analysis.expr.refinement.ExprTraceChecker;
import hu.bme.mit.theta.analysis.expr.refinement.ExprTraceFwBinItpChecker;
import hu.bme.mit.theta.analysis.expr.refinement.ExprTraceStatus;
import hu.bme.mit.theta.analysis.expr.refinement.ItpRefutation;
import hu.bme.mit.theta.analysis.pred.PredPrec;
import hu.bme.mit.theta.analysis.pred.PredState;
import hu.bme.mit.theta.analysis.unit.UnitPrec;
import hu.bme.mit.theta.common.logging.Logger;
import hu.bme.mit.theta.core.model.MutableValuation;
import hu.bme.mit.theta.core.model.Valuation;
import hu.bme.mit.theta.core.type.Expr;
import hu.bme.mit.theta.core.type.booltype.BoolLitExpr;
import hu.bme.mit.theta.core.type.booltype.BoolType;
import hu.bme.mit.theta.core.utils.PathUtils;
import hu.bme.mit.theta.solver.SolverFactory;
import hu.bme.mit.theta.solver.UCSolver;
import hu.bme.mit.theta.solver.utils.WithPushPop;
import hu.bme.mit.theta.solver.z3legacy.Z3LegacySolverFactory;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static hu.bme.mit.theta.core.type.booltype.BoolExprs.Not;
import static hu.bme.mit.theta.core.type.booltype.BoolExprs.Or;
import static hu.bme.mit.theta.core.type.booltype.SmartBoolExprs.And;
import static hu.bme.mit.theta.core.utils.ExprUtils.getConjuncts;

public class CarCegarChecker<S extends ExprState, A extends ExprAction>
        implements SafetyChecker<EmptyProof, Trace<S, A>, UnitPrec> {
    private final MonolithicExpr monolithicExpr;
    private final List<OverFrame> forwardOverFrames;
    private final List<UnderFrame> backwardUnderFrames;
    private final SolverFactory solverFactory;
    private final UCSolver solver;
    private final Function<Valuation, S> valToState;
    private final BiFunction<Valuation, Valuation, A> biValToAction;
    private final boolean formerFramesOpt;
    private final boolean unSatOpt;
    private final boolean notBOpt;
    private final boolean propagateOpt;
    private final boolean filterOpt;
    private int currentFrameNumber;
    private final boolean forwardTrace;
    private final boolean propertyOpt;
    private final Logger logger;

    public CarCegarChecker(
            MonolithicExpr monolithicExpr,
            boolean forwardTrace,
            SolverFactory solverFactory,
            Function<Valuation, S> valToState,
            BiFunction<Valuation, Valuation, A> biValToAction,
            Logger logger) {
        this(
                monolithicExpr,
                forwardTrace,
                solverFactory,
                valToState,
                biValToAction,
                true,
                true,
                true,
                true,
                true,
                true,
                logger);
    }

    public CarCegarChecker(
            MonolithicExpr monolithicExpr,
            boolean forwardTrace,
            SolverFactory solverFactory,
            Function<Valuation, S> valToState,
            BiFunction<Valuation, Valuation, A> biValToAction,
            boolean formerFramesOpt,
            boolean unSatOpt,
            boolean notBOpt,
            boolean propagateOpt,
            boolean filterOpt,
            boolean propertyOpt,
            Logger logger) {
        this.monolithicExpr = monolithicExpr;
        this.valToState = valToState;
        this.biValToAction = biValToAction;
        this.formerFramesOpt = formerFramesOpt;
        this.unSatOpt = unSatOpt;
        this.notBOpt = notBOpt;
        this.propagateOpt = propagateOpt;
        this.filterOpt = filterOpt;
        this.forwardTrace = forwardTrace;
        this.propertyOpt = propertyOpt;
        this.logger = logger;
        this.solverFactory = solverFactory;
        forwardOverFrames = new ArrayList<>();
        backwardUnderFrames = new ArrayList<>();
        solver = solverFactory.createUCSolver();
        forwardOverFrames.add(new OverFrame(null, solver, monolithicExpr));
        forwardOverFrames.get(0).refine(monolithicExpr.getInitExpr());
        backwardUnderFrames.add(new UnderFrame(solver));
        backwardUnderFrames.get(0).expand(Not(monolithicExpr.getPropExpr()),0);
        currentFrameNumber = 0;
    }

    @Override
    public SafetyResult<EmptyProof, Trace<S, A>> check(UnitPrec prec) {
        var predPrec = PredPrec.of(monolithicExpr.getInitExpr()); // todo use unitprec

        while(true){
            logger.write(Logger.Level.SUBSTEP, "Current prec: %s\n", predPrec);
            final var abstractMonolithicExpr =
                    AbstractMonolithicExprKt.createAbstract(monolithicExpr, predPrec);
            var checker =
                    new CarChecker<>(
                            abstractMonolithicExpr,
                            true,
                            Z3LegacySolverFactory.getInstance(),
                            valToState,
                            biValToAction,
                            formerFramesOpt,
                            unSatOpt,
                            notBOpt,
                            propagateOpt,
                            filterOpt,
                            propertyOpt,
                            logger);
            var result = checker.check();
            if (result.isSafe()) {
                logger.write(Logger.Level.MAINSTEP, "Model is safe, stopping CEGAR");
                return SafetyResult.safe(result.getProof());
            }else{
                Preconditions.checkState(result.isUnsafe());
                final Trace<? extends ExprState, ? extends ExprAction> trace =
                        result.asUnsafe().getCex();

                final ExprTraceChecker<ItpRefutation> exprTraceFwBinItpChecker =
                        ExprTraceFwBinItpChecker.create(
                                monolithicExpr.getInitExpr(),
                                Not(monolithicExpr.getPropExpr()),
                                solverFactory.createItpSolver());
                if (trace != null) {
                    logger.write(Logger.Level.VERBOSE, "\tFound trace: %s\n", trace);
                    final ExprTraceStatus<ItpRefutation> concretizationResult =
                            exprTraceFwBinItpChecker.check(trace);
                    if (concretizationResult.isFeasible()) {
                        logger.write(Logger.Level.MAINSTEP, "Model is unsafe, stopping CEGAR\n");

                        return result;
                    } else {
                        final var ref = concretizationResult.asInfeasible().getRefutation();
                        final var newPred = ref.get(ref.getPruneIndex());
                        final var newPrec = PredPrec.of(newPred);
                        predPrec = predPrec.join(newPrec);
                        logger.write(Logger.Level.INFO, "Added new predicate " + newPrec + "\n");
                    }
                }
            }
        }

    }


}
