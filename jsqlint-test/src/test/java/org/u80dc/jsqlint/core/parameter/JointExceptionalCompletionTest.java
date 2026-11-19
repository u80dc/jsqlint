package org.u80dc.jsqlint.core.parameter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.spi.InvocationSite;
import sootup.core.jimple.common.constant.IntConstant;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.JavaSootClass;
import sootup.java.core.views.JavaView;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult.Status.*;

/** 无历史快照的调用方也不能把可到达目标的 catch 路径误判为无解。 */
class JointExceptionalCompletionTest {
    @TempDir Path temporary;

    @Test void directlyInferredParametersRetainUnknownForCaughtNonReturningCall() throws Exception {
        try (Fixture fixture = fixture("""
                static void abort() { throw new IllegalStateException(); }
                static void execute(int id) {}
                static void run() {
                    try { abort(); } catch (RuntimeException caught) {}
                    execute(7);
                }
                """)) {
            InvocationSite execution = fixture.call("run", "execute");
            var point = new FlowPoint(execution.method(), execution.statement(), CallContext.EMPTY);
            var parameter = new ParameterValueInferencer().infer("id", fixture.flow().trace(point.value(IntConstant.getInstance(7))));
            var result = new JointSqlValueInferencer(fixture.flow()).inferParameters(List.of(parameter), point, List.of(), List.of());
            assertEquals(UNKNOWN, result.status(), result.toString());
            assertFalse(result.coverageComplete());
            assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("exceptional")), result.issues().toString());
        }
    }

    @Test void methodWithInternallyCaughtThrowIsNotSummarizedAsNonReturning() throws Exception {
        try (Fixture fixture = fixture("""
                static void recover() {
                    try { throw new IllegalStateException(); } catch (RuntimeException caught) {}
                }
                static void execute(int id) {}
                static void run() { recover(); execute(7); }
                """)) {
            InvocationSite recovery = fixture.call("run", "recover");
            assertFalse(fixture.flow().cannotCompleteNormally(new FlowPoint(recovery.method(), recovery.statement(), CallContext.EMPTY)));
            InvocationSite execution = fixture.call("run", "execute");
            var point = new FlowPoint(execution.method(), execution.statement(), CallContext.EMPTY);
            var parameter = new ParameterValueInferencer().infer("id", fixture.flow().trace(point.value(IntConstant.getInstance(7))));
            var result = new JointSqlValueInferencer(fixture.flow()).inferParameters(List.of(parameter), point, List.of(), List.of());
            assertEquals(SATISFIABLE, result.status(), result.toString());
        }
    }

    private Fixture fixture(String methods) throws Exception {
        Path root = Files.createTempDirectory(temporary, "catch-");
        Path classes = Files.createDirectory(root.resolve("classes"));
        Path source = Files.writeString(root.resolve("Sample.java"), "public class Sample {" + methods + "}");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-g", "-d", classes.toString(), source.toString()));
        var location = new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application);
        var view = new JavaView(location);
        var types = view.getClasses().filter(JavaSootClass::isApplicationClass).toList();
        List<InvocationSite> calls = new ArrayList<>();
        for (var type : types) for (var method : type.getMethods()) if (method.hasBody()) {
            var statements = method.getBody().getStmts();
            for (int i = 0; i < statements.size(); i++) {
                var statement = statements.get(i);
                if (statement.isInvokableStmt()) {
                    var invocation = statement.asInvokableStmt().getInvokeExpr();
                    if (invocation.isPresent()) calls.add(new InvocationSite(method, statement, i, invocation.get(), view));
                }
            }
        }
        var index = new ProgramIndex(view, types, calls, () -> {});
        var flow = new DataFlowAnalysis(index, 32, 256, 10_000, 128, () -> {}, (code, message) -> {});
        return new Fixture(location, flow, List.copyOf(calls));
    }

    private record Fixture(JavaClassPathAnalysisInputLocation location, DataFlowAnalysis flow, List<InvocationSite> calls) implements AutoCloseable {
        InvocationSite call(String caller, String target) {
            return calls.stream().filter(call -> call.method().getName().equals(caller)
                    && call.invocation().getMethodSignature().getName().equals(target)).findFirst().orElseThrow();
        }
        @Override public void close() throws Exception { location.close(); }
    }
}
