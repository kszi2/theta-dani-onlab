package hu.bme.mit.theta.core.type;

import java.util.Optional;

import hu.bme.mit.theta.core.expr.LitExpr;
import hu.bme.mit.theta.core.utils.TypeVisitor;

public interface Type {

	public LitExpr<? extends Type> getAny();

	public boolean isLeq(Type type);

	public Optional<? extends Type> meet(Type type);

	public Optional<? extends Type> join(Type type);

	public <P, R> R accept(TypeVisitor<? super P, ? extends R> visitor, P param);

}