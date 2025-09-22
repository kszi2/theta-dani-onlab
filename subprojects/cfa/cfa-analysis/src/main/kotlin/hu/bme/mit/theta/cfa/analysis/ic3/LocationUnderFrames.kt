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

import hu.bme.mit.theta.analysis.algorithm.ic3.Frame
import hu.bme.mit.theta.cfa.CFA
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.solver.UCSolver

class LocationUnderFrames internal constructor(private val solver: UCSolver) {

    private val frames: MutableList<UnderFrame>
    private var currentFrameNumber: Int


    init {
        frames = ArrayList()
        frames.add(UnderFrame(null, solver))
        currentFrameNumber = 0
    }

    fun addExpr(frameNumber: Int, expr: Expr<BoolType>) {
        frames[frameNumber].expand(expr)
    }

    fun getExprs(currentFrameNumber: Int): Set<Expr<BoolType>> {
        return frames[currentFrameNumber].getExprs()
    }

    fun getFrame(frameNumber: Int): UnderFrame {
        return frames[frameNumber]
    }

    fun getFrames(): MutableList<UnderFrame> {
        return frames
    }

    fun newFrame() {
        frames.add(UnderFrame(frames[currentFrameNumber], solver))
        currentFrameNumber++
    }
}