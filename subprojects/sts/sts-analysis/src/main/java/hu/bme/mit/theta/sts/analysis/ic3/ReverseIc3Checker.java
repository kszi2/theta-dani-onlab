package hu.bme.mit.theta.sts.analysis.ic3;

import hu.bme.mit.theta.analysis.Prec;
import hu.bme.mit.theta.analysis.algorithm.SafetyChecker;
import hu.bme.mit.theta.analysis.algorithm.SafetyResult;
import hu.bme.mit.theta.analysis.algorithm.bounded.ConcreteMonolithicExpr;
import hu.bme.mit.theta.analysis.algorithm.bounded.MonolithicExpr;
import hu.bme.mit.theta.core.type.Expr;
import hu.bme.mit.theta.core.type.anytype.PrimeExpr;
import hu.bme.mit.theta.core.type.booltype.BoolType;
import hu.bme.mit.theta.core.type.booltype.IffExpr;
import hu.bme.mit.theta.solver.SolverFactory;

import java.util.ArrayList;
import java.util.List;

import static hu.bme.mit.theta.core.type.booltype.BoolExprs.Not;
import static hu.bme.mit.theta.core.type.booltype.SmartBoolExprs.And;

public class ReverseIc3Checker implements SafetyChecker {
    private final MonolithicExpr monolithicExpr;
    private final SolverFactory solverFactory;

    public ReverseIc3Checker(MonolithicExpr monolithicExpr, SolverFactory solverFactory) {
        this.monolithicExpr = monolithicExpr;
        this.solverFactory=solverFactory;
    }

    public Expr<BoolType> reverseTransition(Expr<BoolType> trans){
        List<Expr<BoolType>> trans2 = new ArrayList<Expr<BoolType>>();
        for(var ex : trans.getOps()){
            if(ex.getOps().size()==2){

                //Expr<BoolType> reverseEx = IffExpr.of((Expr<BoolType>) PrimeExpr.of(ex.getOps().get(1)), (Expr<BoolType>) ex.getOps().get(0).getOps().get(0));
                Expr<BoolType> reverseEx = IffExpr.of((Expr<BoolType>) PrimeExpr.of(ex.getOps().get(1)), (Expr<BoolType>) ex.getOps().get(0));
                trans2.add(reverseEx);
            }else{
                trans2.add((Expr<BoolType>) ex);
            }

        }
        return And(trans2);
    }

    @Override
    public SafetyResult check(Prec prec) {
        ExprReverser exprReverser = new ExprReverser();
        MonolithicExpr reverseMonolithicExpr = new ConcreteMonolithicExpr(Not(monolithicExpr.prop()), exprReverser.reverse(monolithicExpr.trans()), Not(monolithicExpr.init()), monolithicExpr.offsetIndex());
        Ic3Checker ic3Checker = new Ic3Checker(reverseMonolithicExpr,this.solverFactory);
        return ic3Checker.check(prec);
    }
}
