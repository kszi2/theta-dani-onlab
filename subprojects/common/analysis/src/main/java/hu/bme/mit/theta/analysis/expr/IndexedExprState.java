/*
 *  Copyright 2024 Budapest University of Technology and Economics
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
package hu.bme.mit.theta.analysis.expr;

import hu.bme.mit.theta.common.Utils;
import hu.bme.mit.theta.core.type.Expr;
import hu.bme.mit.theta.core.type.booltype.BoolType;
import hu.bme.mit.theta.core.utils.indexings.VarIndexing;
import hu.bme.mit.theta.core.utils.indexings.VarIndexingFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static hu.bme.mit.theta.core.type.booltype.BoolExprs.False;

public final class IndexedExprState implements ExprState {

    private final Expr<BoolType> indexedExpr;
    private final VarIndexing varIndexing;

    protected IndexedExprState(final Expr<BoolType> indexedExpr, final VarIndexing varIndexing) {
        this.indexedExpr = checkNotNull(indexedExpr);
        this.varIndexing = checkNotNull(varIndexing);
    }

    public static IndexedExprState of(final Expr<BoolType> indexedExpr, final VarIndexing varIndexing) {
        return new IndexedExprState(indexedExpr, varIndexing);
    }

    public static IndexedExprState bottom() {
        return new IndexedExprState(False(), VarIndexingFactory.indexing(0));
    }

    public VarIndexing getVarIndexing() {
        return varIndexing;
    }

    @Override
    public Expr<BoolType> toExpr() {
        return indexedExpr;
    }

    @Override
    public boolean isBottom() {
        return indexedExpr.equals(False());
    }

    @Override
    public String toString() {
        return Utils.lispStringBuilder(getClass().getSimpleName()).body()
                .add(indexedExpr)
                .add(varIndexing.toString())
                .toString();
    }
}