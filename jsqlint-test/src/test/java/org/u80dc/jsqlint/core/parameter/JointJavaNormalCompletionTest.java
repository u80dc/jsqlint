package org.u80dc.jsqlint.core.parameter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.JointBudgets;
import org.u80dc.jsqlint.core.ReplayBudgets;
import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.node.SqlValue;
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

/** 库方法的完成前提属于控制路径，与返回值是否被使用无关。 */
class JointJavaNormalCompletionTest {
    @TempDir Path temporary;

    @Test void discardedResultsCannotMakeDefinitelyThrowingCallsSatisfiable() throws Exception {
        for (String call : List.of("java.util.Objects.requireNonNull(null);", "Integer.parseInt(\"bad\");",
                "\"x\".substring(2);", "\"x\".concat(null);", "\"x\".repeat(-1);", "new String((String) null);")) {
            try (Fixture fixture = fixture("static void run() {" + call + "execute(7);}")) {
                var result = infer(fixture, ReplayBudgets.defaults());
                assertEquals(UNSATISFIABLE, result.status(), call + ": " + result);
                assertTrue(result.coverageComplete(), call);
                assertTrue(result.combinations().isEmpty(), call);
            }
        }
    }

    @Test void validDiscardedLibraryResultsPreserveSatisfiablePaths() throws Exception {
        try (Fixture fixture = fixture("""
                static void run() {
                    java.util.Objects.requireNonNull("kept");
                    Integer.parseInt("7"); "value".substring(1); "x".concat("y");
                    new String("copy"); execute(7);
                }
                """)) {
            var result = infer(fixture, ReplayBudgets.defaults());
            assertEquals(SATISFIABLE, result.status(), result.toString());
            assertTrue(result.coverageComplete());
        }
    }

    @Test void throwingLibraryBranchCannotBeCollapsedAtItsJoin() throws Exception {
        try (Fixture fixture = fixture("""
                static void run(boolean invalid) {
                    if (invalid) Integer.parseInt("bad");
                    execute(7);
                }
                """)) {
            var result = infer(fixture, ReplayBudgets.defaults());
            assertEquals(SATISFIABLE, result.status(), result.toString());
            assertTrue(result.coverageComplete());
            assertEquals(1, result.combinations().size());
            assertTrue(result.combinations().getFirst().inputs().entrySet().stream()
                    .anyMatch(entry -> entry.getKey().type().equals("boolean")
                            && entry.getValue() instanceof SqlValue.Known known && Boolean.FALSE.equals(known.value())));
        }
    }

    @Test void caughtLibraryFailureRetainsUnknownExceptionalCoverage() throws Exception {
        try (Fixture fixture = fixture("""
                static void run() {
                    try { java.util.Objects.requireNonNull(null); } catch (RuntimeException caught) {}
                    execute(7);
                }
                """)) {
            var result = infer(fixture, ReplayBudgets.defaults());
            assertEquals(UNKNOWN, result.status(), result.toString());
            assertFalse(result.coverageComplete());
            assertTrue(result.combinations().stream().noneMatch(value -> value.status() == SATISFIABLE));
        }
    }

    @Test void unknownOperandsAndReplayLimitsDoNotProveFailure() throws Exception {
        for (String method : List.of("static void run(String value) { Integer.parseInt(value); execute(7); }",
                "static void run() { \"x\".repeat(100); execute(7); }")) {
            try (Fixture fixture = fixture(method)) {
                var result = infer(fixture, ReplayBudgets.defaults().withLength(8));
                assertEquals(UNKNOWN, result.status(), result.toString());
            }
        }
    }

    @Test void discardedRecursiveApplicationCallRetainsUnknownNormalReturn() throws Exception {
        try (Fixture fixture = fixture("static void recurse() { recurse(); } static void run() { recurse(); execute(7); }")) {
            var result = infer(fixture, ReplayBudgets.defaults());
            assertEquals(UNKNOWN, result.status(), result.toString());
            assertTrue(result.combinations().stream().noneMatch(value -> value.status() == SATISFIABLE));
        }
    }

    @Test void applicationCallDepthLimitCannotProduceSatisfiableNormalReturn() throws Exception {
        try (Fixture fixture = fixture("""
                static void finish() {}
                static void helper() { finish(); }
                static void run() { helper(); execute(7); }
                """, 1)) {
            var result = infer(fixture, ReplayBudgets.defaults());
            assertEquals(UNKNOWN, result.status(), result.toString());
            assertTrue(result.combinations().stream().noneMatch(value -> value.status() == SATISFIABLE));
        }
    }

    @Test void ordinaryExternalJdbcCallKeepsItsExistingNormalPathDomain() throws Exception {
        try (Fixture fixture = fixture("""
                static void run(java.sql.Statement statement) throws Exception {
                    statement.executeQuery("SELECT 1"); execute(7);
                }
                """)) {
            var result = infer(fixture, ReplayBudgets.defaults());
            assertEquals(SATISFIABLE, result.status(), result.toString());
        }
    }

    @Test void stringReturningLibraryWrapperKeepsItsVerifiedValueModel() throws Exception {
        try (Fixture fixture = fixture("""
                static String helper(String value) { return value.concat("x"); }
                static void executeSql(String sql) {}
                static void run() { executeSql(helper("SELECT ")); }
                """)) {
            InvocationSite execution = fixture.calls().stream().filter(call -> call.method().getName().equals("run")
                    && call.invocation().getMethodSignature().getName().equals("executeSql")).findFirst().orElseThrow();
            var point = new FlowPoint(execution.method(), execution.statement(), CallContext.EMPTY);
            var parameter = new ParameterValueInferencer().infer("sql", fixture.flow().trace(point.value(execution.invocation().getArg(0))));
            var result = new JointSqlValueInferencer(fixture.flow()).inferParameters(List.of(parameter), point, List.of(), List.of());
            assertEquals(SATISFIABLE, result.status(), result.toString());
            assertEquals(new SqlValue.Known("SELECT x"), result.combinations().getFirst().parameters().get("sql").value());
        }
    }

    @Test void discardedLibraryWrapperReusesItsSafetyPrerequisites() throws Exception {
        for (String call : List.of("java.util.Objects.requireNonNull(null);", "Integer.parseInt(\"bad\");")) {
            try (Fixture fixture = fixture("static void helper() {" + call + "} static void run() { helper(); execute(7); }")) {
                var result = infer(fixture, ReplayBudgets.defaults());
                assertEquals(UNSATISFIABLE, result.status(), result.toString());
                assertTrue(result.coverageComplete());
            }
        }
    }

    @Test void conditionalApplicationThrowCannotUseAnUnprovedReturnBranch() throws Exception {
        try (Fixture fixture = fixture("""
                static void check(int number) { if (number == 7) throw new IllegalStateException(); }
                static void run() { check(7); execute(7); }
                """)) {
            var result = infer(fixture, ReplayBudgets.defaults());
            assertEquals(UNKNOWN, result.status(), result.toString());
            assertTrue(result.combinations().stream().noneMatch(value -> value.status() == SATISFIABLE));
        }
    }

    @Test void ordinaryExternalJdbcWrapperDoesNotAddAnApplicationProofRequirement() throws Exception {
        try (Fixture fixture = fixture("""
                static void helper(java.sql.Statement statement) throws Exception { statement.executeQuery("SELECT 1"); }
                static void run(java.sql.Statement statement) throws Exception { helper(statement); execute(7); }
                """)) {
            var result = infer(fixture, ReplayBudgets.defaults());
            assertEquals(SATISFIABLE, result.status(), result.toString());
        }
    }

    @Test void callerEntryDoesNotRequireTheCalleeToFinishAfterItsInternalExecution() throws Exception {
        for (String after : List.of("throw new IllegalStateException();", "recurse();")) {
            try (Fixture fixture = fixture("public static void main(String[] args) { run(); }"
                    + "static void recurse() { recurse(); } static void run() { execute(7); " + after + " }")) {
                var result = infer(fixture, ReplayBudgets.defaults());
                assertEquals(SATISFIABLE, result.status(), result.toString());
            }
        }
    }

    @Test void callerEntryKeepsTheCalleeStringBranchConstraintBeforeExecution() throws Exception {
        try (Fixture fixture = fixture("""
                public static void main(String[] args) { run("age"); }
                static void executeSql(String sql) {}
                static void run(String sort) {
                    String sql = "age".equals(sort) ? "SELECT age" : "SELECT id";
                    executeSql(sql);
                }
                """)) {
            InvocationSite execution = fixture.calls().stream().filter(call -> call.method().getName().equals("run")
                    && call.invocation().getMethodSignature().getName().equals("executeSql")).findFirst().orElseThrow();
            var context = fixture.flow().contexts(execution.method()).getFirst().context();
            var point = new FlowPoint(execution.method(), execution.statement(), context);
            var parameter = new ParameterValueInferencer().infer("sql", fixture.flow().trace(point.value(execution.invocation().getArg(0))));
            var result = new JointSqlValueInferencer(fixture.flow()).inferParameters(List.of(parameter), point, List.of(), List.of());
            assertEquals(SATISFIABLE, result.status(), result.toString());
            assertEquals(1, result.combinations().size());
            assertEquals(new SqlValue.Known("SELECT age"), result.combinations().getFirst().parameters().get("sql").value());
        }
    }

    @Test void allocatedArrayNonNullChecksDoNotRequireArrayContentReplay() throws Exception {
        for (String check : List.of("java.util.Objects.requireNonNull(values);",
                "java.util.Objects.requireNonNull(values, \"SQL values required\");")) {
            try (Fixture fixture = fixture("static void run() { String[] values={\"SELECT 1\"}; " + check + " execute(7); }")) {
                var result = infer(fixture, ReplayBudgets.defaults());
                assertEquals(SATISFIABLE, result.status(), result.toString());
            }
        }
    }

    @Test void nestedCallerEntriesKeepTheInternalExecutionPrefixAtEveryLayer() throws Exception {
        try (Fixture fixture = fixture("""
                public static void main(String[] args) { controller(); }
                static void controller() { findActiveAdults(); }
                static void findActiveAdults() { searchUsers("age"); }
                static void searchUsers(String sort) { findUsers(sort); }
                static void executeSql(String sql) {}
                static void findUsers(String sort) {
                    String sql = "age".equals(sort) ? "SELECT age" : "SELECT id";
                    executeSql(sql);
                    throw new IllegalStateException();
                }
                """)) {
            InvocationSite execution = fixture.calls().stream().filter(call -> call.method().getName().equals("findUsers")
                    && call.invocation().getMethodSignature().getName().equals("executeSql")).findFirst().orElseThrow();
            var context = fixture.flow().contexts(execution.method()).getFirst().context();
            assertEquals(4, context.calls().size());
            var point = new FlowPoint(execution.method(), execution.statement(), context);
            var parameter = new ParameterValueInferencer().infer("sql", fixture.flow().trace(point.value(execution.invocation().getArg(0))));
            var result = new JointSqlValueInferencer(fixture.flow()).inferParameters(List.of(parameter), point, List.of(), List.of());
            assertEquals(SATISFIABLE, result.status(), result.toString());
            assertEquals(1, result.combinations().size());
            assertEquals(new SqlValue.Known("SELECT age"), result.combinations().getFirst().parameters().get("sql").value());
        }
    }

    @Test void modeledMapReceiverIsNonNullWithoutReplayingItsUnknownContents() throws Exception {
        try (Fixture fixture = fixture("static void run(java.util.Map<String,Object> args) { args.get(\"table\"); execute(7); }")) {
            var flow = fixture.flow().withModel(ignored -> (input, dependency) -> {
                if (!(input.value() instanceof sootup.core.jimple.common.ref.JParameterRef)
                        || !input.value().getType().toString().equals("java.util.Map")) return Optional.empty();
                var variable = input.snapshot(fixture.flow().program());
                var key = new ValueTrace.Node(variable.withType("java.lang.String"), ValueTrace.Kind.CONSTANT, "map key", List.of(),
                        new ValueTrace.Computation(ValueTrace.Operation.LITERAL, "table"));
                var unknown = new ValueTrace.Node(variable.withType("java.lang.Object"), ValueTrace.Kind.UNRESOLVED, "Map value is unknown", List.of());
                return Optional.of(new ValueTrace.Node(variable, ValueTrace.Kind.PARAMETER, "modeled Map snapshot", List.of(key, unknown),
                        new ValueTrace.Computation(ValueTrace.Operation.MAP_LITERAL, "")));
            });
            InvocationSite execution = fixture.calls().stream().filter(call -> call.invocation().getMethodSignature().getName().equals("execute"))
                    .findFirst().orElseThrow();
            var point = new FlowPoint(execution.method(), execution.statement(), CallContext.EMPTY);
            var parameter = new ParameterValueInferencer().infer("id", flow.trace(point.value(IntConstant.getInstance(7))));
            var result = new JointSqlValueInferencer(flow).inferParameters(List.of(parameter), point, List.of(), List.of());
            assertEquals(SATISFIABLE, result.status(), result.toString());
        }
    }

    @Test void realNullAndUnknownMapReceiversKeepTheirCompletionConstraints() throws Exception {
        try (Fixture fixture = fixture("static void run() { ((java.util.Map<?,?>) null).get(\"table\"); execute(7); }")) {
            var result = infer(fixture, ReplayBudgets.defaults());
            assertEquals(UNSATISFIABLE, result.status(), result.toString());
        }
        try (Fixture fixture = fixture("static void run(java.util.Map<String,Object> args) { args.get(\"table\"); execute(7); }")) {
            var result = infer(fixture, ReplayBudgets.defaults());
            assertEquals(UNKNOWN, result.status(), result.toString());
        }
    }

    @Test void unselectedApplicationEndpointCannotIgnoreItsDefiniteFailure() throws Exception {
        try (Fixture fixture = fixture("static void abort(int id) { throw new IllegalStateException(); } static void run() { abort(7); }")) {
            InvocationSite execution = fixture.calls().stream().filter(call -> call.method().getName().equals("run")
                    && call.invocation().getMethodSignature().getName().equals("abort")).findFirst().orElseThrow();
            var point = new FlowPoint(execution.method(), execution.statement(), CallContext.EMPTY);
            var parameter = new ParameterValueInferencer().infer("id", fixture.flow().trace(point.value(IntConstant.getInstance(7))));
            var result = new JointSqlValueInferencer(fixture.flow()).inferParameters(List.of(parameter), point, List.of(), List.of());
            assertEquals(UNSATISFIABLE, result.status(), result.toString());
            assertTrue(result.combinations().isEmpty());
        }
    }

    @Test void discardedBranchingStringHelperUsesOnlyItsNormalReturnProof() throws Exception {
        try (Fixture fixture = fixture("""
                static String pattern(String keyword) {
                    String normalized = keyword == null ? "" : keyword.trim();
                    String escaped = normalized.replace("!", "!!").replace("%", "!%").replace("_", "!_");
                    return "%" + escaped + "%";
                }
                static void run(String keyword) { pattern(keyword); execute(7); }
                """)) {
            var result = infer(fixture, ReplayBudgets.defaults());
            assertEquals(SATISFIABLE, result.status(), result.toString());
            assertTrue(result.coverageComplete());
        }
    }

    @Test void branchingStringHelperExcludesItsDefinitelyFailingReturnPath() throws Exception {
        try (Fixture fixture = fixture("""
                static String helper(boolean invalid) {
                    String value = invalid ? (String) null : "valid";
                    return value.trim();
                }
                static void run() { helper(true); execute(7); }
                """)) {
            var result = infer(fixture, ReplayBudgets.defaults());
            assertEquals(UNSATISFIABLE, result.status(), result.toString());
            assertTrue(result.coverageComplete());
            assertTrue(result.combinations().isEmpty());
        }
    }

    @Test void selectedStringReturnCanProveTheSafeSideOfAnApplicationThrow() throws Exception {
        try (Fixture fixture = fixture("""
                static String helper(boolean invalid) {
                    if (invalid) throw new IllegalStateException();
                    return "safe".trim();
                }
                static void executeSql(String sql) {}
                static void run() { executeSql(helper(false)); }
                """)) {
            InvocationSite execution = fixture.calls().stream().filter(call -> call.method().getName().equals("run")
                    && call.invocation().getMethodSignature().getName().equals("executeSql")).findFirst().orElseThrow();
            var point = new FlowPoint(execution.method(), execution.statement(), CallContext.EMPTY);
            var parameter = new ParameterValueInferencer().infer("sql", fixture.flow().trace(point.value(execution.invocation().getArg(0))));
            var result = new JointSqlValueInferencer(fixture.flow()).inferParameters(List.of(parameter), point, List.of(), List.of());
            assertEquals(SATISFIABLE, result.status(), result.toString());
            assertEquals(new SqlValue.Known("safe"), result.combinations().getFirst().parameters().get("sql").value());
        }
    }

    @Test void branchingStringCompletionProofRetainsItsControlFlowBudget() throws Exception {
        try (Fixture fixture = fixture("""
                static String pattern(String keyword) {
                    String normalized = keyword == null ? "" : keyword.trim();
                    String escaped = normalized.replace("!", "!!").replace("%", "!%").replace("_", "!_");
                    return "%" + escaped + "%";
                }
                static void run(String keyword) { pattern(keyword); execute(7); }
                """)) {
            var result = infer(fixture, ReplayBudgets.defaults(), JointBudgets.defaults().withControlFlowDepth(6));
            assertEquals(UNKNOWN, result.status(), result.toString());
            assertFalse(result.coverageComplete());
            assertTrue(result.combinations().stream().noneMatch(value -> value.status() == SATISFIABLE));
        }
    }

    @Test void selectedCalleeCompletionKeepsTheCallingArrayStoreBounds() throws Exception {
        try (Fixture fixture = fixture("""
                static String helper(boolean upper) { return upper ? "valid".trim() : "kept".trim(); }
                static void run(int index) {
                    String[] values = new String[1];
                    values[index] = helper(true);
                    execute(7);
                }
                """)) {
            var result = infer(fixture, ReplayBudgets.defaults());
            assertEquals(SATISFIABLE, result.status(), result.toString());
            assertTrue(result.combinations().stream().allMatch(combination -> combination.inputs().entrySet().stream()
                    .filter(entry -> entry.getKey().type().equals("int"))
                    .allMatch(entry -> new SqlValue.Known(0).equals(entry.getValue()))));
        }
    }

    @Test void selectedArrayReadKeepsTheBranchAllocationBounds() throws Exception {
        for (boolean invalid : List.of(true, false)) {
            try (Fixture fixture = fixture("""
                    static int helper(boolean invalid, int index) {
                        int[] values = invalid ? new int[0] : new int[1];
                        return values[index];
                    }
                    static void run() { helper(%s, 0); execute(7); }
                    """.formatted(invalid))) {
                var result = infer(fixture, ReplayBudgets.defaults());
                assertEquals(invalid ? UNSATISFIABLE : SATISFIABLE, result.status(), result.toString());
                assertTrue(result.coverageComplete());
            }
        }
    }

    @Test void selectedArrayWriteKeepsTheBranchAllocationBounds() throws Exception {
        for (boolean invalid : List.of(true, false)) {
            try (Fixture fixture = fixture("""
                    static String helper(boolean invalid, int index) {
                        int[] values = invalid ? new int[0] : new int[1];
                        values[index] = 7;
                        return "ok".trim();
                    }
                    static void run() { helper(%s, 0); execute(7); }
                    """.formatted(invalid))) {
                var result = infer(fixture, ReplayBudgets.defaults());
                assertEquals(invalid ? UNSATISFIABLE : SATISFIABLE, result.status(), result.toString());
                assertTrue(result.coverageComplete());
            }
        }
    }

    @Test void selectedNullArrayAlternativeCannotProveNormalReturn() throws Exception {
        for (boolean invalid : List.of(true, false)) {
            try (Fixture fixture = fixture("""
                    static String helper(boolean invalid) {
                        int[] values = invalid ? null : new int[1];
                        values[0] = 7;
                        return "ok".trim();
                    }
                    static void run() { helper(%s); execute(7); }
                    """.formatted(invalid))) {
                var result = infer(fixture, ReplayBudgets.defaults());
                assertEquals(invalid ? UNSATISFIABLE : SATISFIABLE, result.status(), result.toString());
                assertTrue(result.coverageComplete());
            }
        }
    }

    @Test void selectedUnknownArrayBoundsRemainUnknownWhileKnownAlternativeCanReturn() throws Exception {
        for (boolean supplied : List.of(true, false)) {
            try (Fixture fixture = fixture("""
                    static int helper(boolean supplied, int[] input) {
                        int[] values = supplied ? input : new int[1];
                        return values[0];
                    }
                    static void run(int[] input) { helper(%s, input); execute(7); }
                    """.formatted(supplied))) {
                var result = infer(fixture, ReplayBudgets.defaults());
                assertEquals(supplied ? UNKNOWN : SATISFIABLE, result.status(), result.toString());
                if (supplied) assertTrue(result.combinations().stream().noneMatch(value -> value.status() == SATISFIABLE));
            }
        }
    }

    @Test void discardedIntegerDivisionAndRemainderCannotReturnWithZeroDivisor() throws Exception {
        for (String type : List.of("int", "long")) for (String operation : List.of("/", "%")) {
            try (Fixture fixture = fixture("""
                    static String helper(boolean invalid) {
                        %s divisor = invalid ? 0 : 1;
                        %s ignored = 10 %s divisor;
                        return "ok".trim();
                    }
                    static void run() { helper(true); execute(7); }
                    """.formatted(type, type, operation))) {
                var result = infer(fixture, ReplayBudgets.defaults());
                assertEquals(UNSATISFIABLE, result.status(), result.toString());
                assertTrue(result.coverageComplete());
            }
        }
    }

    @Test void validDiscardedIntegerDivisionAndRemainderPreserveNormalReturn() throws Exception {
        for (String type : List.of("int", "long")) for (String operation : List.of("/", "%")) {
            try (Fixture fixture = fixture("""
                    static String helper(boolean invalid) {
                        %s divisor = invalid ? 0 : -1;
                        %s ignored = 10 %s divisor;
                        return "ok".trim();
                    }
                    static void run() { helper(false); execute(7); }
                    """.formatted(type, type, operation))) {
                var result = infer(fixture, ReplayBudgets.defaults());
                assertEquals(SATISFIABLE, result.status(), result.toString());
                assertTrue(result.coverageComplete());
            }
        }
    }

    @Test void floatingPointZeroDivisionAndRemainderCompleteNormally() throws Exception {
        for (String type : List.of("float", "double")) for (String operation : List.of("/", "%")) {
            try (Fixture fixture = fixture("""
                    static String helper(boolean zero) {
                        %s divisor = zero ? 0 : 1;
                        %s ignored = 10 %s divisor;
                        return "ok".trim();
                    }
                    static void run() { helper(true); execute(7); }
                    """.formatted(type, type, operation))) {
                var result = infer(fixture, ReplayBudgets.defaults());
                assertEquals(SATISFIABLE, result.status(), result.toString());
                assertTrue(result.coverageComplete());
            }
        }
    }

    @Test void discardedIntegerFailureBranchCannotCollapseAtItsJoin() throws Exception {
        try (Fixture fixture = fixture("""
                static void run(boolean invalid) {
                    if (invalid) {
                        int divisor = 0;
                        int ignored = 10 / divisor;
                    }
                    execute(7);
                }
                """)) {
            var result = infer(fixture, ReplayBudgets.defaults());
            assertEquals(SATISFIABLE, result.status(), result.toString());
            assertTrue(result.coverageComplete());
            assertEquals(1, result.combinations().size());
            assertTrue(result.combinations().getFirst().inputs().entrySet().stream()
                    .anyMatch(entry -> entry.getKey().type().equals("boolean")
                            && new SqlValue.Known(false).equals(entry.getValue())));
        }
    }

    private JointSqlInferenceResult infer(Fixture fixture, ReplayBudgets replay) {
        return infer(fixture, replay, JointBudgets.defaults());
    }

    private JointSqlInferenceResult infer(Fixture fixture, ReplayBudgets replay, JointBudgets budgets) {
        InvocationSite execution = fixture.calls().stream().filter(call -> call.method().getName().equals("run")
                && call.invocation().getMethodSignature().getName().equals("execute")).findFirst().orElseThrow();
        var point = new FlowPoint(execution.method(), execution.statement(), fixture.flow().contexts(execution.method()).getFirst().context());
        var parameter = new ParameterValueInferencer().infer("id", fixture.flow().trace(point.value(IntConstant.getInstance(7))));
        return new JointSqlValueInferencer(fixture.flow(), budgets, RandomExternalValueGenerator::new, replay)
                .inferParameters(List.of(parameter), point, List.of(), List.of());
    }

    private Fixture fixture(String methods) throws Exception {
        return fixture(methods, 32);
    }

    private Fixture fixture(String methods, int maxCallDepth) throws Exception {
        Path root = Files.createTempDirectory(temporary, "java-completion-");
        Path classes = Files.createDirectory(root.resolve("classes"));
        Path source = Files.writeString(root.resolve("Sample.java"), "public class Sample {static void execute(int id) {}" + methods + "}");
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
        var flow = new DataFlowAnalysis(new ProgramIndex(view, types, calls, () -> {}), maxCallDepth, 256, 10_000, 128,
                () -> {}, (code, message) -> {});
        return new Fixture(location, flow, List.copyOf(calls));
    }

    private record Fixture(JavaClassPathAnalysisInputLocation location, DataFlowAnalysis flow, List<InvocationSite> calls) implements AutoCloseable {
        @Override public void close() throws Exception { location.close(); }
    }
}
