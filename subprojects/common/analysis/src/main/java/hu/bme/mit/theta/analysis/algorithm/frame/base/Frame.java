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
package hu.bme.mit.theta.analysis.algorithm.frame.base;

import static hu.bme.mit.theta.core.type.booltype.SmartBoolExprs.*;

import hu.bme.mit.theta.analysis.algorithm.bounded.MonolithicExpr;
import hu.bme.mit.theta.common.logging.Logger;
import hu.bme.mit.theta.common.logging.NullLogger;
import hu.bme.mit.theta.core.model.Valuation;
import hu.bme.mit.theta.core.type.Expr;
import hu.bme.mit.theta.core.type.booltype.BoolType;
import hu.bme.mit.theta.core.utils.PathUtils;
import hu.bme.mit.theta.core.utils.indexings.VarIndexing;
import hu.bme.mit.theta.core.utils.indexings.VarIndexingFactory;
import hu.bme.mit.theta.solver.UCSolver;
import hu.bme.mit.theta.solver.utils.WithPushPop;
import java.util.*;

public class Frame {
    private final Frame parent;
    private final List<Clause> clauses;

    private final UCSolver solver;
    private final BaseOptimizations optimizations;
    private MonolithicExpr monolithicExpr;
    private final Logger logger;
    private final Expr<BoolType> originalProp;

    public Frame(
            final Frame parent,
            UCSolver solver,
            MonolithicExpr monolithicExpr,
            BaseOptimizations optimizations) {
        this(parent, solver, monolithicExpr, optimizations, NullLogger.getInstance());
    }

    public Frame(
            final Frame parent,
            UCSolver solver,
            MonolithicExpr monolithicExpr,
            BaseOptimizations optimizations,
            Logger logger) {
        this.parent = parent;
        this.solver = solver;
        this.monolithicExpr = monolithicExpr;
        this.originalProp = monolithicExpr.getPropExpr();
        this.optimizations = optimizations;
        this.logger = logger;
        clauses = new ArrayList<>();
    }

    public void setMonolithicExpr(MonolithicExpr monolithicExpr) {
        this.monolithicExpr = monolithicExpr;
    }

    public List<Clause> getClauses() {
        return clauses;
    }

    public Expr<BoolType> getExpression() {
        final List<Expr<BoolType>> exprs = new ArrayList<>();
        for (Clause clause : clauses) {
            exprs.add(clause.toExpr());
        }
        return And(exprs);
    }

    public void addFrameToSolver(VarIndexing indexing) {
        addFrameToSolver(indexing, originalProp);
    }

    private void addFrameToSolver(VarIndexing indexing, Expr<BoolType> prop) {
        if (parent == null) {
            solver.track(PathUtils.unfold(monolithicExpr.getInitExpr(), indexing));
            return;
        }
        for (Clause clause : clauses) {
            solver.track(PathUtils.unfold(clause.toExpr(), indexing));
        }
        if (optimizations.isPropertyOpt()) {
            solver.track(PathUtils.unfold(prop, indexing));
        }
    }

    public void addNegatedFrameToSolver(VarIndexing indexing) {
        addNegatedFrameToSolver(indexing, originalProp);
    }

    private void addNegatedFrameToSolver(VarIndexing indexing, Expr<BoolType> prop) {
        if (parent == null) {
            solver.track(PathUtils.unfold(Not(monolithicExpr.getInitExpr()), indexing));
            return;
        }
        final List<Expr<BoolType>> exprs = new ArrayList<>();
        for (Clause clause : clauses) {
            exprs.add(clause.negate().toExpr());
        }
        if (optimizations.isPropertyOpt()) {
            exprs.add(Not(prop));
        }
        solver.track(PathUtils.unfold(Or(exprs), indexing));
    }

    public void refine(Cube blockedCube) {
        Clause newClause = blockedCube.negate();
        Clause oldClause = null;
        for (Clause clause : clauses) {

            if (clause.subsumes(newClause)) {
                oldClause = clause;
            }
            if (newClause.subsumes(clause)) {
                logger.write(
                        Logger.Level.VERBOSE,
                        "\tFrame: rejecting clause %s, already covered by existing stronger/equal"
                                + " clause %s (frame still has %d clauses)%n",
                        newClause,
                        clause,
                        clauses.size());
                return;
            }
        }

        if (oldClause != null) {
            clauses.remove(oldClause);
            logger.write(
                    Logger.Level.VERBOSE,
                    "\tFrame: replacing weaker clause %s with stronger clause %s (frame now has"
                            + " %d clauses)%n",
                    oldClause,
                    newClause,
                    clauses.size() + 1);
        } else {
            logger.write(
                    Logger.Level.VERBOSE,
                    "\tFrame: adding new clause %s (frame now has %d clauses)%n",
                    newClause,
                    clauses.size() + 1);
        }
        clauses.add(newClause);
    }

    public Valuation checkIfTargetIsReachableValuation(Expr<BoolType> target) {
        try (var wpp = new WithPushPop(solver)) {
            addFrameToSolver(VarIndexingFactory.indexing(0));
            TransitionRelation.addToSolver(solver, monolithicExpr);
            solver.track(PathUtils.unfold(target, monolithicExpr.getTransOffsetIndex()));
            if (solver.check().isSat()) {
                return solver.getModel();
            } else {
                return null;
            }
        }
    }

    public Valuation checkIfContainsValuation(Expr<BoolType> target) {
        try (var wpp = new WithPushPop(solver)) {
            solver.track(PathUtils.unfold(target, 0));
            addFrameToSolver(VarIndexingFactory.indexing(0));
            if (solver.check().isSat()) {
                return solver.getModel();
            } else {
                return null;
            }
        }
    }

    /**
     * The property a fixpoint check conjoins this frame with. A frame keeps the property of the
     * model it was built with; after a CEGAR refinement that one lacks the new predicates'
     * definitions. Queries with the transition relation don't notice (it asserts the new
     * definitions), but a fixpoint check has no transition relation, so there states that
     * violate a new definition satisfy an old frame's negation, and old and new frames never
     * compare equal.
     */
    private Expr<BoolType> fixpointProp(boolean currentProp) {
        return currentProp ? monolithicExpr.getPropExpr() : originalProp;
    }

    public boolean equalsAllParents() {
        return equalsAllParents(false);
    }

    /**
     * @param currentProp compare every frame under the current model's property instead of the
     *     one it was built with (see {@link #fixpointProp})
     */
    public boolean equalsAllParents(boolean currentProp) {
        if (this.parent == null) {
            return false;
        }
        try (var wpp = new WithPushPop(solver)) {
            var currentParent = this.parent;
            while (currentParent != null) {
                currentParent.addNegatedFrameToSolver(
                        VarIndexingFactory.indexing(0), currentParent.fixpointProp(currentProp));
                currentParent = currentParent.parent;
            }

            addFrameToSolver(VarIndexingFactory.indexing(0), fixpointProp(currentProp));

            return solver.check().isUnsat();
        }
    }

    public boolean equalsParent() {
        return equalsParent(false);
    }

    /**
     * @param currentProp compare both frames under the current model's property instead of the
     *     one each was built with (see {@link #fixpointProp})
     */
    public boolean equalsParent(boolean currentProp) {
        if (this.parent == null) {
            return false;
        }
        try (var wpp = new WithPushPop(solver)) {

            parent.addNegatedFrameToSolver(
                    VarIndexingFactory.indexing(0), parent.fixpointProp(currentProp));
            addFrameToSolver(VarIndexingFactory.indexing(0), fixpointProp(currentProp));

            return solver.check().isUnsat();
        }
    }
}
