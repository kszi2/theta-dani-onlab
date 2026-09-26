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

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import hu.bme.mit.theta.analysis.algorithm.bounded.MonolithicExpr;
import hu.bme.mit.theta.core.type.Expr;
import hu.bme.mit.theta.core.type.booltype.BoolType;
import hu.bme.mit.theta.core.utils.PathUtils;
import hu.bme.mit.theta.solver.Solver;
import hu.bme.mit.theta.solver.UCSolver;

/**
 * Adds a model's transition relation (unfolded from index 0) to a solver.
 *
 * <p>It is added as a single untracked assertion: no caller looks for transition conjuncts in an
 * unsat core, and tracking each conjunct costs a fresh solver label per conjunct on every query
 * (about a million per query on large AIGER circuits, whose AND-gate definitions are all trans
 * conjuncts). The unfolded expression is cached per model, so the solver's term cache recognizes
 * the same object on later queries instead of translating the whole relation again.
 */
public final class TransitionRelation {

    // weak keys compare by identity; an abstract model replaced during refinement is dropped
    private static final LoadingCache<MonolithicExpr, Expr<BoolType>> UNFOLDED =
            CacheBuilder.newBuilder()
                    .weakKeys()
                    .build(CacheLoader.from(model -> PathUtils.unfold(model.getTransExpr(), 0)));

    private TransitionRelation() {}

    public static void addToSolver(final UCSolver solver, final MonolithicExpr model) {
        final Expr<BoolType> trans = UNFOLDED.getUnchecked(model);
        if (solver instanceof Solver plainSolver) {
            plainSolver.add(trans);
        } else {
            solver.track(trans);
        }
    }
}
