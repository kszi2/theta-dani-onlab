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

import hu.bme.mit.theta.analysis.Action;
import hu.bme.mit.theta.analysis.Trace;
import hu.bme.mit.theta.analysis.algorithm.EmptyProof;
import hu.bme.mit.theta.analysis.algorithm.SafetyChecker;
import hu.bme.mit.theta.analysis.algorithm.SafetyResult;
import hu.bme.mit.theta.analysis.algorithm.bounded.MonolithicExpr;
import hu.bme.mit.theta.analysis.algorithm.bounded.MonolithicExprKt;
import hu.bme.mit.theta.analysis.expr.ExprAction;
import hu.bme.mit.theta.analysis.expr.ExprState;
import hu.bme.mit.theta.analysis.expr.refinement.ExprTraceChecker;
import hu.bme.mit.theta.analysis.expr.refinement.ExprTraceFwBinItpChecker;
import hu.bme.mit.theta.analysis.expr.refinement.ExprTraceStatus;
import hu.bme.mit.theta.analysis.expr.refinement.ItpRefutation;
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

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static hu.bme.mit.theta.core.type.booltype.BoolExprs.Not;
import static hu.bme.mit.theta.core.type.booltype.BoolExprs.Or;
import static hu.bme.mit.theta.core.type.booltype.SmartBoolExprs.And;
import static hu.bme.mit.theta.core.utils.ExprUtils.getConjuncts;

public class CarChecker<S extends ExprState, A extends ExprAction>
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

    private final HashMap<Node, Boolean> currentlyVisited;

    private Node root;

    public List<MutableValuation> getValuations() {
        return valuations;
    }

    private List<MutableValuation> valuations;

    public CarChecker(
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

    public CarChecker(
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
        valuations = new ArrayList<>();
        root = new Node(Not(monolithicExpr.getPropExpr()),null);
        currentlyVisited = new HashMap<>();
        currentlyVisited.put(root,false);
    }

    private Node getNotCheckedNode(){
        for(Node node : currentlyVisited.keySet()){ //todo can be more faster if the nodes visited in a more specific order
            if(!currentlyVisited.get(node)){
                currentlyVisited.put(node,true);
                return node;
            }
        }
        return null;
    }

    @Override
    public SafetyResult<EmptyProof, Trace<S, A>> check(UnitPrec prec) {
        // check if init violates prop
        var firstTrace = checkFirst();
        if (firstTrace != null) {
            final var result = SafetyResult.unsafe(firstTrace, EmptyProof.getInstance());
            logger.writeln(Logger.Level.RESULT, result.toString());
            return result;
        }
        while (true) {
            while(true){
                Node node = getNotCheckedNode();
                if(node == null){
                    break;
                }
                Node counterExampleNode = checkCurrentFrame(node);
                if (counterExampleNode != null) {
                    var faultyNode =
                            tryBlock(new ProofObligation(counterExampleNode, currentFrameNumber));
                    if (faultyNode != null) {
                        var trace = makeTrace(faultyNode);
                        final var result = SafetyResult.unsafe(trace, EmptyProof.getInstance());
                        logger.writeln(Logger.Level.RESULT, result.toString());
                        return result;
                    }
                }
            }
            if (propagate()) {
                final SafetyResult<EmptyProof, Trace<S, A>> result =
                        SafetyResult.safe(EmptyProof.getInstance());
                logger.writeln(Logger.Level.RESULT, result.toString());
                return result;
            }

        }
    }

    public void prune(int backwardIndex, int index){
        if(backwardUnderFrames.size()-1>=backwardIndex+1){
            int childSize = backwardUnderFrames.get(backwardIndex+1).getParents().size();
            for(int i = 0; i < childSize; i++){
                if(backwardUnderFrames.get(backwardIndex+1).getParents().get(i)==index){
                    prune(backwardIndex+1,i);
                }
            }
        }
        var exp = backwardUnderFrames.get(backwardIndex).getExprsinList().get(index);
        backwardUnderFrames.get(backwardIndex).getExprs().remove(exp);
        backwardUnderFrames.get(backwardIndex).getParents().remove(index);
        backwardUnderFrames.get(backwardIndex).getExprsinList().remove(index);


        if(backwardUnderFrames.size()-1>=backwardIndex+1){
            for(int i = 0; i < backwardUnderFrames.get(backwardIndex+1).getParents().size(); i++){
                var oldValue = backwardUnderFrames.get(backwardIndex+1).getParents().get(i);
                if(oldValue>index){
                    backwardUnderFrames.get(backwardIndex+1).getParents().set(i,oldValue-1);
                }
            }

        }
        if(backwardUnderFrames.get(backwardIndex).getParents().size()==0){
            backwardUnderFrames.remove(backwardIndex);
        }
    }

    Node tryBlock(ProofObligation mainProofObligation) {
        final LinkedList<ProofObligation> proofObligationsQueue = new LinkedList<ProofObligation>();
        proofObligationsQueue.add(mainProofObligation);
        while (!proofObligationsQueue.isEmpty()) {
            final ProofObligation proofObligation = proofObligationsQueue.getLast();

            if (proofObligation.getTime() == 0) {
                return proofObligation.getNode();
            }

            final Collection<Expr<BoolType>> b;
            final Collection<Expr<BoolType>> unSatCore;
            try (var wpp = new WithPushPop(solver)) {

                forwardOverFrames.get(proofObligation.getTime() - 1)
                        .getExprs()
                        .forEach(ex -> solver.track(PathUtils.unfold(ex, 0)));
                if (notBOpt) {
                    solver.track(PathUtils.unfold(Not(And(proofObligation.getNode().getExprs())), 0));
                }
                if (proofObligation.getTime() > 2 && formerFramesOpt) { // lehet, hogy 1, vagy 2??
                    solver.track(
                            PathUtils.unfold(
                                    Not(And(forwardOverFrames.get(proofObligation.getTime() - 2).getExprs())),
                                    monolithicExpr
                                            .getTransOffsetIndex())); // 2 vel korábbi frame-ban
                    // levő dolgok
                }

                getConjuncts(monolithicExpr.getTransExpr())
                        .forEach(ex -> solver.track(PathUtils.unfold(ex, 0)));
                proofObligation
                        .getNode()
                        .getExprs()
                        .forEach(
                                ex ->
                                        solver.track(
                                                PathUtils.unfold(
                                                        ex, monolithicExpr.getTransOffsetIndex())));

                if (solver.check().isSat()) {
                    final Valuation model = solver.getModel();

                    final MutableValuation filteredModel = new MutableValuation();
                    monolithicExpr.getVars().stream()
                            .map(varDecl -> varDecl.getConstDecl(0))
                            .filter(model.toMap()::containsKey)
                            .forEach(decl -> filteredModel.put(decl, model.eval(decl).get()));
                    if (filterOpt) {
                        var vars = new HashSet<>(filteredModel.toMap().keySet());
                        for (var var : vars) {
                            if (!(var.getType() instanceof BoolType)) {
                                continue;
                            }
                            var origValue = model.eval(var).get();
                            var negatedValue =
                                    BoolLitExpr.of(!((BoolLitExpr) origValue).getValue());
                            filteredModel.put(var, negatedValue);
                            try (var wpp2 = new WithPushPop(solver)) {
                                solver.track(PathUtils.unfold(filteredModel.toExpr(), 0));
                                if (solver.check().isSat()) {
                                    filteredModel.remove(var);
                                } else {
                                    filteredModel.put(var, origValue);
                                }
                            }
                        }
                    }
                    b =
                            getConjuncts(
                                    PathUtils.foldin(
                                            PathUtils.extractValuation(filteredModel, 0).toExpr(),
                                            0));
                    unSatCore = null;
                } else {
                    b = null;
                    unSatCore = solver.getUnsatCore();
                }
            }
            if (b == null) {

                final Collection<Expr<BoolType>> newCore = new ArrayList<Expr<BoolType>>();
                newCore.addAll(proofObligation.getNode().getExprs());
                if (unSatOpt) {
                    for (Expr<BoolType> i : proofObligation.getNode().getExprs()) {
                        if (!unSatCore.contains(
                                PathUtils.unfold(i, monolithicExpr.getTransOffsetIndex()))) {
                            newCore.remove(i);
                            final boolean isSat;
                            try (var wpp = new WithPushPop(solver)) {
                                for (Expr<BoolType> solverex : newCore) {
                                    solver.track(PathUtils.unfold(solverex, 0));
                                }
                                solver.track(PathUtils.unfold(monolithicExpr.getInitExpr(), 0));
                                isSat = solver.check().isSat();
                            }
                            if (isSat) {
                                newCore.add(i);
                            }
                        }
                    }
                }
                for (int i = 1; i <= proofObligation.getTime(); ++i) {
                    forwardOverFrames.get(i).refine(Not(And(newCore)));
                }
                proofObligationsQueue.removeLast();
            } else {
                Node newNode = new Node(And(b),proofObligation.getNode());
                currentlyVisited.put(newNode,true);
                proofObligationsQueue.add(new ProofObligation(newNode, proofObligation.getTime() - 1));
            }
        }
        return null;
    }

    public Trace<S, A> checkFirst() {
        try (var wpp = new WithPushPop(solver)) {
            solver.track(
                    PathUtils.unfold(
                            monolithicExpr.getInitExpr(), monolithicExpr.getInitOffsetIndex()));
            solver.track(
                    PathUtils.unfold(
                            Not(monolithicExpr.getPropExpr()),
                            monolithicExpr.getInitOffsetIndex()));
            if (solver.check().isSat()) {
                return Trace.of(
                        List.of(
                                valToState.apply(
                                        PathUtils.extractValuation(
                                                solver.getModel(),
                                                monolithicExpr.getInitOffsetIndex(),
                                                monolithicExpr.getVars()))),
                        List.of());
            }
        }
        if (propertyOpt) {
            try (var wpp = new WithPushPop(solver)) {
                solver.track(
                        PathUtils.unfold(
                                monolithicExpr.getInitExpr(), monolithicExpr.getInitOffsetIndex()));
                solver.track(
                        PathUtils.unfold(
                                monolithicExpr.getTransExpr(),
                                monolithicExpr.getInitOffsetIndex()));
                solver.track(
                        PathUtils.unfold(
                                Not(monolithicExpr.getPropExpr()),
                                monolithicExpr.getTransOffsetIndex()));
                if (solver.check().isSat()) {
                    return Trace.of(
                            List.of(
                                    valToState.apply(
                                            PathUtils.extractValuation(
                                                    solver.getModel(),
                                                    monolithicExpr.getInitOffsetIndex(),
                                                    monolithicExpr.getVars())),
                                    valToState.apply(
                                            PathUtils.extractValuation(
                                                    solver.getModel(),
                                                    monolithicExpr.getTransOffsetIndex(),
                                                    monolithicExpr.getVars()))),
                            List.of(
                                    biValToAction.apply(
                                            PathUtils.extractValuation(
                                                    solver.getModel(),
                                                    monolithicExpr.getInitOffsetIndex(),
                                                    monolithicExpr.getVars()),
                                            PathUtils.extractValuation(
                                                    solver.getModel(),
                                                    monolithicExpr.getTransOffsetIndex(),
                                                    monolithicExpr.getVars()))));
                } else {
                    return null;
                }
            }
        } else {
            return null;
        }
    }

    private int currentIndexOfUnder;
    public Node checkCurrentFrame(Node target) {
        if (propertyOpt) {
            for(var underEx : target.getExprs()){
                try (var wpp = new WithPushPop(solver)) {
                    forwardOverFrames.get(currentFrameNumber)
                            .getExprs()
                            .forEach(ex -> solver.track(PathUtils.unfold(ex, 0)));
                    getConjuncts(monolithicExpr.getTransExpr())
                            .forEach(ex -> solver.track(PathUtils.unfold(ex, 0)));
                    solver.track(PathUtils.unfold(underEx, monolithicExpr.getTransOffsetIndex()));
                    if (solver.check().isSat()) {
                        final Valuation model = solver.getModel();
                        final MutableValuation filteredModel = new MutableValuation();
                        monolithicExpr.getVars().stream()
                                .map(varDecl -> varDecl.getConstDecl(0))
                                .filter(model.toMap()::containsKey)
                                .forEach(decl -> filteredModel.put(decl, model.eval(decl).get()));

                        var counterExample = getConjuncts(PathUtils.foldin(filteredModel.toExpr(), 0));

                        Node newNode = new Node(And(counterExample),target);

                        currentlyVisited.put(newNode,true);

                        return newNode;

                    }
                }
            }
            return null;


        } else {
            var counterExample = forwardOverFrames.get(currentFrameNumber).check(And(target.getExprs()));
            if(counterExample == null){
                return null; //no intersection found
            }else{
                Node newNode = new Node(And(counterExample),target);

                currentlyVisited.put(newNode,true);
                return newNode;
            }
        }
    }
    private void noNodeIsVisited(){
        for(Node node : currentlyVisited.keySet()){
            currentlyVisited.put(node,false);
        }
    }

    public boolean propagate() {
        noNodeIsVisited();
        forwardOverFrames.add(new OverFrame(forwardOverFrames.get(currentFrameNumber), solver, monolithicExpr));
        currentFrameNumber++;
        if (propertyOpt) {
            forwardOverFrames.get(currentFrameNumber).refine(monolithicExpr.getPropExpr());
        }

        if (propagateOpt) {
            for (int j = 1; j < currentFrameNumber; j++) {
                for (var c : forwardOverFrames.get(j).getExprs()) {
                    try (var wpp = new WithPushPop(solver)) {
                        forwardOverFrames.get(j)
                                .getExprs()
                                .forEach(ex -> solver.track(PathUtils.unfold(ex, 0)));
                        getConjuncts(monolithicExpr.getTransExpr())
                                .forEach(ex -> solver.track(PathUtils.unfold(ex, 0)));
                        solver.track(
                                PathUtils.unfold(Not(c), monolithicExpr.getTransOffsetIndex()));
                        if (solver.check().isUnsat()) {
                            forwardOverFrames.get(j + 1).refine(c);
                        }
                    }
                }
                if (forwardOverFrames.get(j + 1).equalsParent()) {
                    return true;
                }
            }
        } else if (currentFrameNumber > 1 && forwardOverFrames.get(currentFrameNumber - 1).equalsParent()) {
            return true;
        }
        return false;
    }

    public Trace<S, A> makeTrace(Node faultyNode) {
        var abstractStates = new ArrayList<ExprState>();
        var abstractActions = new ArrayList<ExprAction>();
        Node currentNode = faultyNode;



        while (currentNode != null) {


            if (!abstractStates.isEmpty())
                abstractActions.add(MonolithicExprKt.action(monolithicExpr));
            abstractStates.add(PredState.of(currentNode.getExprs()));
            currentNode = currentNode.getParent();

        }

        final ExprTraceChecker<ItpRefutation> checker =
                ExprTraceFwBinItpChecker.create(
                        monolithicExpr.getInitExpr(),
                        Not(monolithicExpr.getPropExpr()),
                        solverFactory.createItpSolver());
        final ExprTraceStatus<ItpRefutation> status =
                checker.check(Trace.of(abstractStates, abstractActions));
        checkArgument(status.isFeasible(), "Infeasible trace.");

        Trace<Valuation, ? extends Action> trace = status.asFeasible().getValuations();
        if (!forwardTrace) trace = trace.reverse();
        valuations =
                trace.getStates().stream()
                        .map(
                                it -> {
                                    final MutableValuation newValuation = new MutableValuation();
                                    it.toMap().entrySet().stream()
                                            .filter(
                                                    entry ->
                                                            monolithicExpr
                                                                    .getVars()
                                                                    .contains(entry.getKey()))
                                            .forEach(
                                                    entry ->
                                                            newValuation.put(
                                                                    entry.getKey(),
                                                                    entry.getValue()));
                                    return newValuation;
                                })
                        .toList();
        final List<S> states = new ArrayList<>();
        final List<A> actions = new ArrayList<>();
        for (int i = 0; i < valuations.size(); ++i) {
            states.add(valToState.apply(valuations.get(i)));
            if (i > 0) {
                actions.add(biValToAction.apply(valuations.get(i - 1), valuations.get(i)));
            }
        }


        return Trace.of(states, actions);
    }

}
