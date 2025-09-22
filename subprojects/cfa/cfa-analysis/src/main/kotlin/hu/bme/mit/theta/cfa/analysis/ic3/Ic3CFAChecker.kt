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
import hu.bme.mit.theta.analysis.expl.ExplState
import hu.bme.mit.theta.analysis.expr.ExprAction
import hu.bme.mit.theta.analysis.expr.ExprState
import hu.bme.mit.theta.analysis.unit.UnitPrec
import hu.bme.mit.theta.cfa.CFA
import hu.bme.mit.theta.cfa.analysis.CfaAction
import hu.bme.mit.theta.cfa.analysis.CfaState
import hu.bme.mit.theta.common.logging.Logger
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolExprs
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.core.type.booltype.FalseExpr
import hu.bme.mit.theta.core.type.booltype.SmartBoolExprs
import hu.bme.mit.theta.core.type.booltype.SmartBoolExprs.And
import hu.bme.mit.theta.core.utils.ExprUtils
import hu.bme.mit.theta.core.utils.PathUtils
import hu.bme.mit.theta.core.utils.StmtUtils
import hu.bme.mit.theta.core.utils.WpState
import hu.bme.mit.theta.core.utils.indexings.VarIndexingFactory
import hu.bme.mit.theta.solver.SolverFactory
import hu.bme.mit.theta.solver.UCSolver
import hu.bme.mit.theta.solver.utils.WithPushPop
import java.util.*
import java.util.function.Consumer
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class Ic3CFAChecker<S : ExprState?, A : ExprAction?>(
    private val cfa: CFA,
    private val solverFactory: SolverFactory,
    private val logger: Logger) : SafetyChecker<EmptyProof?, Trace<S, A>?, UnitPrec?> {

    private var currentFrameNumber: Int
    private val solver: UCSolver
    private val locations: HashMap<CFA.Loc, LocationOverFrames>

    init {
        solver = solverFactory.createUCSolver()
        locations = HashMap()
        cfa.locs.forEach(Consumer { loc: CFA.Loc -> locations[loc] = LocationOverFrames(solver) })
        for(loc in cfa.locs){
            if(!cfa.initLoc.equals(loc)){
                locations[loc]!!.getFrame(0).refine(FalseExpr.getInstance())
            }
        }


        currentFrameNumber = 0
    }

    fun tryBlock(mainProofObligation: ProofObligation): Boolean {
        val proofObligationsQueue = LinkedList<ProofObligation>()
        proofObligationsQueue.add(mainProofObligation)
        while (!proofObligationsQueue.isEmpty()) {
            val proofObligation = proofObligationsQueue.last
            if (proofObligation.time == 0) {
                return false
            }
            for (loc in cfa.locs) {
                for (edge in loc.outEdges) {
                    if (edge.target == proofObligation.loc) {
                        val b: Set<Expr<BoolType>>?
                        WithPushPop(solver).use { wpp ->
                            locations[loc]!!.getExprs(proofObligation.time-1).forEach(
                                Consumer { ex: Expr<BoolType>? -> solver.track(PathUtils.unfold(ex, 0)) })
                            solver.track(PathUtils.unfold(WpState.of(And(proofObligation.expressions)).wep(edge.stmt).expr, 0))
                            /*val unfoldResult = StmtUtils.toExpr(edge.stmt, VarIndexingFactory.indexing(0))
                            unfoldResult.exprs.forEach(
                                Consumer { expr: Expr<BoolType>? -> solver.track(PathUtils.unfold(expr, 0)) })
                            proofObligation.expressions.forEach(
                                Consumer { ex: Expr<BoolType>? -> solver.track(PathUtils.unfold(ex, unfoldResult.indexing)) }) //indexing??
                            */
                            b = if (solver.check().isSat) {
                                val model = solver.model

                                HashSet(ExprUtils.getConjuncts(
                                    PathUtils.foldin(PathUtils.extractValuation(model, 0).toExpr(), 0)))

                            } else {
                                null
                            }
                        }
                        if (b == null) {
                            for (i in 1..proofObligation.time) {
                                locations[proofObligation.loc]!!.getFrame(i).refine(
                                    BoolExprs.Not(SmartBoolExprs.And(proofObligation.expressions)))
                            }
                            proofObligationsQueue.removeLast()
                        } else {
                            proofObligationsQueue.add(ProofObligation(b, proofObligation.time - 1, loc))

                        }
                    }
                }
            }
        }
        return true
    }

    fun checkFirst(): Trace<S, A>? {
        if (cfa.initLoc == cfa.errorLoc.get()) { //init location equals the error location
            val stateList = arrayListOf<S>()
            val actionList = arrayListOf<A>()
            return Trace.of(stateList, actionList)
        }
        for (edge in cfa.initLoc.outEdges) {
            if (edge.target == cfa.errorLoc.get()) {
                WithPushPop(solver).use { wpp ->
                    val unfoldResult = StmtUtils.toExpr(edge.stmt, VarIndexingFactory.indexing(0))
                    unfoldResult.exprs.forEach{solver.track(PathUtils.unfold(it, 0))}
                    if (solver.check().isSat) {
                        return Trace.of(null, null)
                    }
                }
            }
        }
        return null
    }

    override fun check(input: UnitPrec?): SafetyResult<EmptyProof?, Trace<S, A>?> {
        // check if init violates prop
        val firstTrace = checkFirst()
        if (firstTrace != null) {
            val result = SafetyResult.unsafe(firstTrace, EmptyProof.getInstance())
            logger.writeln(Logger.Level.RESULT, result.toString())
            return result
        }

        //adding false to every loc, which is not init
        cfa.locs.forEach(Consumer { loc: CFA.Loc -> locations[loc]!!.newFrame() })
        currentFrameNumber++
        while (true) {
            var found = 0
            for (loc in cfa.locs) {
                if (loc != cfa.errorLoc.get()) {
                    for (edge in loc.outEdges) {
                        if (edge.target == cfa.errorLoc.get()) {
                            var b: Collection<Expr<BoolType>>?
                            WithPushPop(solver).use { wpp ->
                                locations[loc]!!.getExprs(currentFrameNumber).forEach { solver.track(PathUtils.unfold(it, 0)) }
                                val unfoldResult = StmtUtils.toExpr(edge.stmt, VarIndexingFactory.indexing(0))
                                unfoldResult.exprs.forEach{ solver.track(PathUtils.unfold(it, 0)) }
                                b = if (solver.check().isSat) {
                                    val model = solver.model
                                    ExprUtils.getConjuncts(PathUtils.foldin(model.toExpr(), 0))
                                } else {
                                    null
                                }
                            }
                            if (b != null) {
                                found++
                                val proofObligation = ProofObligation(HashSet(b), currentFrameNumber, loc)
                                if (!tryBlock(proofObligation)) {

                                    val sbeStates: MutableList<CfaState<*>> = ArrayList()
                                    val sbeActions: MutableList<CfaAction> = ArrayList()

                                    sbeStates.add(CfaState.of<ExplState>(cfa.initLoc, ExplState.top()))
                                   // sbeActions.add(CfaAction.create(edges.get(e)))
                                    //actionList.add(CfaAction.create(cfa.))

                                    val trace = Trace.of(sbeStates, sbeActions)
                                    val result: SafetyResult<EmptyProof?, Trace<S, A>?> = SafetyResult.unsafe(trace as Trace<S, A>?, EmptyProof.getInstance())
                                    logger.writeln(Logger.Level.RESULT, result.toString())
                                    return result
                                }
                            }
                        }
                    }
                }
            }
            if (found == 0) {
                for(i in 1..currentFrameNumber){
                    var over = true;
                    for (loc in cfa.locs) {
                        if (!loc.equals(cfa.errorLoc) && !locations[loc]!!.getFrame(i).equalsParent()) {
                            over = false
                            break
                        }
                    }
                    if(over) {
                        return SafetyResult.safe(EmptyProof.getInstance())
                    }
                }

                cfa.locs.forEach(Consumer { loc: CFA.Loc -> locations[loc]!!.newFrame() })
                currentFrameNumber++
            }
        }
    }
}