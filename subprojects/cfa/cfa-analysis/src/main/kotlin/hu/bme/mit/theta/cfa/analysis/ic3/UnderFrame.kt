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

package hu.bme.mit.theta.cfa.analysis.ic3

import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.core.type.booltype.SmartBoolExprs
import hu.bme.mit.theta.core.utils.ExprUtils
import hu.bme.mit.theta.core.utils.PathUtils
import hu.bme.mit.theta.solver.UCSolver
import hu.bme.mit.theta.solver.utils.WithPushPop

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

class UnderFrame internal constructor(private val parent: UnderFrame?,
    private val solver: UCSolver,  // ez nem biztos, hogy kell ide
    ) {

    private val exprs: MutableSet<Expr<BoolType>>

    init {

        exprs = HashSet()
    }

    fun expand(expression: Expr<BoolType>) {

        exprs.add(expression)

    }

    fun getExprs(): Set<Expr<BoolType>> {
        return exprs
    }



    fun equalsParent(): Boolean {
        if (parent!!.parent == null) {
            return false
        }
        WithPushPop(solver).use { wpp ->
            solver.track(PathUtils.unfold(SmartBoolExprs.Not(SmartBoolExprs.And(
                parent.getExprs())), 0))
            solver.track(PathUtils.unfold(SmartBoolExprs.And(exprs), 0))
            return solver.check().isUnsat
        }
    }
}