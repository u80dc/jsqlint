package org.u80dc.jsqlint.core.dataflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.spi.InvocationSite;
import org.u80dc.jsqlint.core.spi.SqlCallSite;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.JavaSootClass;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;
import sootup.core.jimple.common.constant.IntConstant;

import static org.junit.jupiter.api.Assertions.*;

/** 验证共享调用者索引的不可变边界，以及隐式调用注册与实际上下文的独立性。 @author u80dc */
class ProgramIndexTest {
    @TempDir Path temporary;

    @Test
    void callersCannotBeChangedThroughTheReturnedMapOrNestedLists() throws Exception {
        try (var location = new JavaClassPathAnalysisInputLocation(compile().toString(), SourceType.Application)) {
            var fixture = fixture(new JavaView(location));
            var calls = new ArrayList<>(fixture.calls());
            var index = new ProgramIndex(fixture.view(), List.of(fixture.clazz()), calls, () -> {});
            var flow = flow(index);
            var before = flow.contexts(fixture.leaf());
            assertEquals(Set.of("left", "right"), before.stream()
                    .map(path -> path.context().calls().getFirst().method().getName()).collect(Collectors.toSet()));
            assertTrue(before.stream().allMatch(DataFlowAnalysis.ContextPath::complete));

            var returned = index.callers();
            var leafCallers = returned.get(fixture.leaf().getSignature());
            assertEquals(fixture.calls(), leafCallers);
            assertThrows(UnsupportedOperationException.class, returned::clear);
            assertThrows(UnsupportedOperationException.class,
                    () -> returned.put(fixture.leaf().getSignature(), List.of()));
            assertThrows(UnsupportedOperationException.class, leafCallers::clear);
            assertThrows(UnsupportedOperationException.class, () -> leafCallers.add(fixture.calls().getFirst()));
            calls.clear();

            assertEquals(fixture.calls(), index.callers().get(fixture.leaf().getSignature()));
            assertEquals(before, flow.contexts(fixture.leaf()));
            assertEquals(before, flow(index).contexts(fixture.leaf()), "新查询与已缓存的调用上下文应保持一致");
        }
    }

    @Test
    void statementPositionsAndFlowSnapshotsUseTheSameCachedLocation() throws Exception {
        try (var location = new JavaClassPathAnalysisInputLocation(compile().toString(), SourceType.Application)) {
            var fixture = fixture(new JavaView(location));
            AtomicInteger checkpoints = new AtomicInteger();
            var index = new ProgramIndex(fixture.view(), List.of(fixture.clazz()), fixture.calls(), checkpoints::incrementAndGet);
            checkpoints.set(0);
            int statements = 0;
            for (var method : fixture.clazz().getMethods()) {
                if (!method.hasBody()) continue;
                var body = method.getBody().getStmts(); statements += body.size();
                for (int i = 0; i < body.size(); i++) {
                    var statement = body.get(i);
                    assertEquals(i, index.statementIndex(method, statement));
                    var point = new FlowPoint(method, statement, CallContext.EMPTY);
                    assertEquals(point.value(IntConstant.getInstance(0)).snapshot(),
                            point.value(IntConstant.getInstance(0)).snapshot(index));
                    assertEquals(point.invocation(fixture.view()), point.invocation(index));
                }
            }
            assertEquals(statements, checkpoints.get(), "每个方法的位置表只扫描一次");
            for (int round = 0; round < 100; round++) for (var method : fixture.clazz().getMethods()) {
                if (!method.hasBody()) continue;
                var body = method.getBody().getStmts();
                for (int i = 0; i < body.size(); i++) assertEquals(i, index.statementIndex(method, body.get(i)));
            }
            assertEquals(statements, checkpoints.get(), "重复定位使用会话缓存，不重新扫描方法");
            var foreign = fixture.calls().getFirst().statement();
            assertEquals(fixture.leaf().getBody().getStmts().indexOf(foreign), index.statementIndex(fixture.leaf(), foreign),
                    "不属于该方法的语句保留旧 indexOf 的兼容结果");
        }
    }

    @Test
    void largeMethodPositionQueriesHaveLinearIndexingWork() throws Exception {
        int calls = 1500;
        String body = "leaf(\"value\");".repeat(calls);
        Path classes = compile("public class Sample { static String leaf(String value) { return value; } static void large() { " + body + " } }");
        try (var location = new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application)) {
            var view = new JavaView(location);
            var clazz = view.getClasses().filter(JavaSootClass::isApplicationClass).findFirst().orElseThrow();
            var method = clazz.getMethods().stream().filter(candidate -> candidate.getName().equals("large")).findFirst().orElseThrow();
            AtomicInteger checkpoints = new AtomicInteger();
            var index = new ProgramIndex(view, List.of(clazz), List.of(), checkpoints::incrementAndGet);
            var statements = method.getBody().getStmts();
            assertTrue(statements.size() >= calls);
            for (int round = 0; round < 10; round++) for (int i = 0; i < statements.size(); i++)
                assertEquals(i, index.statementIndex(method, statements.get(i)));
            assertEquals(statements.size(), checkpoints.get(), "1500 个调用的重复查询仅建立一次线性位置表");
        }
    }

    @Test
    void implicitInvocationsRemainQueryableWithoutChangingActualCallerContexts() throws Exception {
        try (var location = new JavaClassPathAnalysisInputLocation(compile().toString(), SourceType.Application)) {
            var fixture = fixture(new JavaView(location));
            var left = fixture.calls().stream().filter(site -> site.method().getName().equals("left")).findFirst().orElseThrow();
            var right = fixture.calls().stream().filter(site -> site.method().getName().equals("right")).findFirst().orElseThrow();
            var index = new ProgramIndex(fixture.view(), List.of(fixture.clazz()), List.of(left), () -> {});
            var actualCallers = index.callers();
            var before = flow(index).contexts(fixture.leaf());
            assertTrue(index.invocation(SqlCallSite.invocation(right)).isEmpty());

            index.registerImplicitInvocation(right);

            assertEquals(right, index.invocation(SqlCallSite.invocation(right)).orElseThrow());
            assertEquals(right, index.invocation(right.method().getSignature(), right.statementIndex(),
                    right.invocation().getMethodSignature()).orElseThrow());
            for (int i = 0; i < 100; i++) {
                assertSame(left, index.invocation(left.method().getSignature(), left.statementIndex(),
                        left.invocation().getMethodSignature()).orElseThrow());
                assertSame(right, index.invocation(right.method().getSignature(), right.statementIndex(),
                        right.invocation().getMethodSignature()).orElseThrow());
            }
            assertEquals(List.of(left), actualCallers.get(fixture.leaf().getSignature()));
            assertEquals(before, flow(index).contexts(fixture.leaf()));
        }
    }

    private static DataFlowAnalysis flow(ProgramIndex index) {
        return new DataFlowAnalysis(index, 32, 256, 10000, 128, () -> {}, (code, message) -> {});
    }

    private record Fixture(JavaView view, JavaSootClass clazz, JavaSootMethod leaf, List<InvocationSite> calls) {}

    private static Fixture fixture(JavaView view) {
        var clazz = view.getClasses().filter(JavaSootClass::isApplicationClass).findFirst().orElseThrow();
        var leaf = clazz.getMethods().stream().filter(method -> method.getName().equals("leaf")).findFirst().orElseThrow();
        List<InvocationSite> calls = new ArrayList<>();
        for (var method : clazz.getMethods()) {
            if (!method.hasBody()) continue;
            var statements = method.getBody().getStmts();
            for (int i = 0; i < statements.size(); i++) {
                var statement = statements.get(i);
                if (!statement.isInvokableStmt()) continue;
                var invocation = statement.asInvokableStmt().getInvokeExpr();
                if (invocation.isPresent() && invocation.get().getMethodSignature().equals(leaf.getSignature()))
                    calls.add(new InvocationSite(method, statement, i, invocation.get(), view));
            }
        }
        assertEquals(2, calls.size());
        return new Fixture(view, clazz, leaf, List.copyOf(calls));
    }

    private Path compile() throws Exception {
        return compile("""
                public class Sample {
                    static String leaf(String value) { return value; }
                    static String left() { return leaf("left"); }
                    static String right() { return leaf("right"); }
                }
                """);
    }
    private Path compile(String text) throws Exception {
        Path source = Files.writeString(temporary.resolve("Sample.java"), text);
        Path classes = Files.createDirectory(temporary.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-g", "-d", classes.toString(), source.toString()));
        return classes;
    }
}
