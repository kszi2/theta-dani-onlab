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

import hu.bme.mit.theta.core.type.Expr;
import hu.bme.mit.theta.core.type.booltype.BoolType;

import java.util.HashSet;
import java.util.Set;

public class Node {
    public Set<Expr<BoolType>> getExprs() {
        return exprs;
    }
    public Node getParent() {
        return parent;
    }

    public void setExprs(Set<Expr<BoolType>> exprs) {
        this.exprs = exprs;
    }

    private Set<Expr<BoolType>> exprs;



    private Node parent;


    public Node(Expr<BoolType> expr, Node parent) {
        exprs = new HashSet<Expr<BoolType>>();
        exprs.add(expr);
        this.parent = parent;
    }

}
