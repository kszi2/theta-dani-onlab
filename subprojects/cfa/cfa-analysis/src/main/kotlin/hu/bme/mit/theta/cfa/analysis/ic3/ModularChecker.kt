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

import hu.bme.mit.theta.analysis.Trace
import hu.bme.mit.theta.analysis.algorithm.EmptyProof
import hu.bme.mit.theta.analysis.algorithm.SafetyChecker
import hu.bme.mit.theta.analysis.algorithm.SafetyResult
import hu.bme.mit.theta.analysis.expr.ExprAction
import hu.bme.mit.theta.analysis.expr.ExprState
import hu.bme.mit.theta.analysis.unit.UnitPrec
import hu.bme.mit.theta.cfa.CFA
import hu.bme.mit.theta.cfa.CfaUtils
import hu.bme.mit.theta.common.logging.Logger
import hu.bme.mit.theta.solver.SolverFactory
import hu.bme.mit.theta.solver.UCSolver
import java.util.*

class ModularChecker<S : ExprState?, A : ExprAction?>(
    private val cfa: CFA,
    private val solverFactory: SolverFactory,
    private val logger: Logger) : SafetyChecker<EmptyProof?, Trace<S, A>?, UnitPrec?> {

    private val solver: UCSolver


    init {
        solver = solverFactory.createUCSolver()
    }

    //gets an input list of cutpoints in a cfa, and then returns two lists, which are defined by the cutpoints
    fun splitList(cfaOriginal: CFA, cutLocList: Collection<CFA.Loc>): Pair<MutableCollection<CFA.Loc>,MutableCollection<CFA.Loc>>{
        val firstList: MutableCollection<CFA.Loc> = mutableListOf<CFA.Loc>()

        val locQueue: Queue<CFA.Loc> = LinkedList()
        locQueue.add(cfaOriginal.initLoc)
        while(!locQueue.isEmpty()){
            val currentLoc: CFA.Loc = locQueue.remove()
            firstList.add(currentLoc)
            if(!cutLocList.contains(currentLoc)){
                for(edge: CFA.Edge in currentLoc.outEdges){
                    if(!firstList.contains(edge.target)){
                        locQueue.add(edge.target)
                    }
                }
            }
        }
        var secondList: MutableCollection<CFA.Loc> = cfaOriginal.locs.filter { it !in firstList }.toMutableList()
        return Pair(firstList,secondList)
    }
    fun split(cfaOriginal: CFA): Pair<CFA,CFA> {
        val cutLocList = cfaOriginal.locs.filter { it.name.equals("L6") }
        var (firstLocs, secondLocs) = splitList(cfaOriginal, cutLocList)
        val firstCfa = CfaUtils.restrictToLocations(cfaOriginal,firstLocs,cfaOriginal.initLoc,cfaOriginal.errorLoc.get(),cfaOriginal.finalLoc.get())
        //firstLocs.add(cfaOriginal.errorLoc.get()) todo add errorloc and edges, from cutlocs


        val secondCfa = CfaUtils.restrictToLocations(cfaOriginal,secondLocs,cfaOriginal.initLoc,cfaOriginal.errorLoc.get(),cfaOriginal.finalLoc.get())

        return Pair(firstCfa, secondCfa)
    }



    override fun check(input: UnitPrec?): SafetyResult<EmptyProof?, Trace<S, A>?> {
        // check if init violates prop
        val (cfaFirst, cfaSecond) = split(cfa)
        return SafetyResult.safe(EmptyProof.getInstance());
    }
}