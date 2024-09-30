package hu.bme.mit.theta.sts.analysis.ic3;

import hu.bme.mit.theta.analysis.Prec;
import hu.bme.mit.theta.analysis.algorithm.SafetyChecker;
import hu.bme.mit.theta.analysis.algorithm.SafetyResult;
import hu.bme.mit.theta.analysis.algorithm.bounded.ConcreteMonolithicExpr;
import hu.bme.mit.theta.analysis.algorithm.bounded.MonolithicExpr;
import hu.bme.mit.theta.core.utils.PathUtils;
import hu.bme.mit.theta.solver.SolverFactory;
import hu.bme.mit.theta.solver.UCSolver;
import hu.bme.mit.theta.solver.utils.WithPushPop;

import java.util.*;

import static hu.bme.mit.theta.core.type.anytype.Exprs.Prime;
import static hu.bme.mit.theta.core.type.booltype.SmartBoolExprs.*;

public class ConnectedIc3Checker implements SafetyChecker {
    private final MonolithicExpr monolithicExpr;
    private final List<Frame> frames;
    private final UCSolver solver;

    private final boolean formerFramesOpt;

    private final boolean unSatOpt;

    private final boolean notBOpt;
    private final boolean propagateOpt;
    private final boolean filterOpt;

    private final SolverFactory solverFactory;

    public ConnectedIc3Checker(MonolithicExpr monolithicExpr, SolverFactory solverFactory) {
        this(monolithicExpr, solverFactory, true, true, true, true, true);
    }

    public ConnectedIc3Checker(MonolithicExpr monolithicExpr, SolverFactory solverFactory, boolean formerFramesOpt, boolean unSatOpt, boolean notBOpt, boolean propagateOpt, boolean filterOpt) {
        this.monolithicExpr = monolithicExpr;
        this.formerFramesOpt = formerFramesOpt;
        this.unSatOpt = unSatOpt;
        this.notBOpt = notBOpt;
        this.propagateOpt = propagateOpt;
        this.filterOpt = filterOpt;
        frames = new ArrayList<>();
        solver = solverFactory.createUCSolver();
        this.solverFactory = solverFactory;
    }



    @Override
    public SafetyResult check(Prec prec) {
        //check if init violates prop
        try (var wpp = new WithPushPop(solver)) {
            solver.track(PathUtils.unfold(monolithicExpr.init(), 0));
            solver.track(PathUtils.unfold(Not(monolithicExpr.prop()), 0));
            if (solver.check().isSat()) {
                return SafetyResult.unsafe();
                // return null; //todo mutablevaluation itt is létrehoz
            }
        }

        StepIc3Checker forward = new StepIc3Checker(monolithicExpr, solverFactory,formerFramesOpt,unSatOpt,notBOpt,propagateOpt,filterOpt);

        ExprReverser exprReverser = new ExprReverser();

        MonolithicExpr reverseMonolithicExpr = new ConcreteMonolithicExpr(Not(monolithicExpr.prop()), exprReverser.reverse(monolithicExpr.trans()), Not(monolithicExpr.init()), monolithicExpr.offsetIndex());

        StepIc3Checker backward = new StepIc3Checker(reverseMonolithicExpr, solverFactory,formerFramesOpt,unSatOpt,notBOpt,propagateOpt,filterOpt);

        if(!forward.checkFirst() || !backward.checkFirst()){
            return SafetyResult.unsafe();
        }
        while (true) {
           var counterExample = forward.checkCurrentFrame(And(backward.getcurrentFrame()));
           if(counterExample==null){
               if(forward.propagate()){
                   return SafetyResult.safe();
               }
           }else{
               Boolean isBlocked = forward.tryBlock(new ProofObligation(new HashSet<>(counterExample), forward.getCurrentFrameNumber()));
               if(!isBlocked){
                   if(!backward.tryBlock(new ProofObligation(new HashSet<>(counterExample), backward.getCurrentFrameNumber()))){
                       return SafetyResult.unsafe();
                   }
               }
           }
           counterExample = backward.checkCurrentFrame(Not(And(forward.getcurrentFrame())));
           if(counterExample==null){
               if(backward.propagate()){
                   return SafetyResult.safe();
               }
           }else{
               Boolean isBlocked = backward.tryBlock(new ProofObligation(new HashSet<>(counterExample), backward.getCurrentFrameNumber()));
               if(!isBlocked){
                   if(!forward.tryBlock(new ProofObligation(new HashSet<>(counterExample), forward.getCurrentFrameNumber()))){
                       return SafetyResult.unsafe();
                   }
               }
           }


//            if(forward.getCounterExample() == null){
//                forward.setProp(Not(And(backward.getcurrentFrame())));
//            }

//            Set<Expr<BoolType>> counterExample = forward.step(prec);
//            if(counterExample != null){
//                backward.setCounterExample(counterExample);
//                if(forward.isFaulty()){
//                    return SafetyResult.unsafe();
//                }
//            }else if(forward.isSafe()) {
//                return SafetyResult.safe();
//            }
//
//            if(backward.getCounterExample() == null) {
//                backward.setProp(Not(And(forward.getcurrentFrame())));
//            }
//
//
//            counterExample = backward.step(prec);
//            if(counterExample != null){
//                forward.setCounterExample(counterExample);
//                if(backward.isFaulty()){
//                    return SafetyResult.unsafe();
//                }
//            }else if(backward.isSafe()) {
//                return SafetyResult.safe();
//            }

        }
    }
}
