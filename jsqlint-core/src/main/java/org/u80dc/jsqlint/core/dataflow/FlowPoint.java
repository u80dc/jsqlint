package org.u80dc.jsqlint.core.dataflow;

import org.u80dc.jsqlint.core.spi.*;
import sootup.core.jimple.common.Value;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;
import java.util.*;

/** 仅供分析期间使用的语句与调用上下文，结果通过 snapshot 脱离 SootUp 生命周期。 @author u80dc */
public record FlowPoint(JavaSootMethod method, Stmt statement, CallContext context) {
    public FlowPoint { Objects.requireNonNull(method); Objects.requireNonNull(statement); Objects.requireNonNull(context); }
    public ValuePoint value(Value value) { return new ValuePoint(this, value); }
    public Optional<InvocationSite> invocation(JavaView view) {
        if (!statement.isInvokableStmt()) return Optional.empty();
        return statement.asInvokableStmt().getInvokeExpr().map(expr -> new InvocationSite(
                method, statement, method.getBody().getStmts().indexOf(statement), expr, view));
    }
    /** 分析内部使用共享程序索引，避免每次查询重新扫描语句列表。 */
    public Optional<InvocationSite> invocation(ProgramIndex program) {
        if (!statement.isInvokableStmt()) return Optional.empty();
        return statement.asInvokableStmt().getInvokeExpr().map(expr -> new InvocationSite(
                method, statement, program.statementIndex(method, statement), expr, program.view()));
    }
    public record ValuePoint(FlowPoint point, Value value) {
        public ValuePoint { Objects.requireNonNull(point); Objects.requireNonNull(value); }
        public SqlInput.ValueAt input() { return new SqlInput.ValueAt(point.method(), point.statement(), value); }
        public ValueTrace.Variable snapshot() {
            return new ValueTrace.Variable(point.method().getSignature(),
                    point.method().getBody().getStmts().indexOf(point.statement()), value.toString(), value.getType().toString(),
                    point.context().snapshot());
        }
        public ValueTrace.Variable snapshot(ProgramIndex program) {
            return new ValueTrace.Variable(point.method().getSignature(),
                    program.statementIndex(point.method(), point.statement()), value.toString(), value.getType().toString(),
                    point.context().snapshot());
        }
    }
}
