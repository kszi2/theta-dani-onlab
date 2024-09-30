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

public class ReverseIc3Checker extends Ic3Checker {


    public ReverseIc3Checker(MonolithicExpr monolithicExpr, SolverFactory solverFactory, boolean formerFramesOpt, boolean unSatOpt, boolean notBOpt, boolean propagateOpt, boolean filterOpt) {
        super(new ConcreteMonolithicExpr(Not(monolithicExpr.prop()), new ExprReverser().reverse(monolithicExpr.trans()), Not(monolithicExpr.init()), monolithicExpr.offsetIndex()),solverFactory,formerFramesOpt,unSatOpt,notBOpt,propagateOpt,filterOpt);
    }
    public ReverseIc3Checker(MonolithicExpr monolithicExpr, SolverFactory solverFactory) {
        this(monolithicExpr, solverFactory, true, true, true, true, true);
    }
}
