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
import hu.bme.mit.theta.solver.UCSolver

class LocationOverFrames internal constructor(private val solver: UCSolver) {

    private val frames: MutableList<OverFrame>
    private var currentFrameNumber: Int //todo maybe the class is not needed and an arraylist is enough


    init {
        frames = ArrayList()
        frames.add(OverFrame(null, solver))
        currentFrameNumber = 0
    }

    fun addExpr(frameNumber: Int, expr: Expr<BoolType?>?) {
        frames[frameNumber].refine(expr)
    }

    fun getExprs(currentFrameNumber: Int): Set<Expr<BoolType>> {
        return frames[currentFrameNumber].getExprs()
    }

    fun getFrame(frameNumber: Int): OverFrame {
        return frames[frameNumber]
    }

    fun newFrame() {
        frames.add(OverFrame(frames[currentFrameNumber], solver))
        currentFrameNumber++
    }
}