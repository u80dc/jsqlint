package org.u80dc.jsqlint.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.parameter.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult.Status.*;

/** 使用实际编译的分支字节码验证 SQL、多个槽位和绑定参数的联合路径，而非独立列表的笛卡尔积。 @author u80dc */
class JdbcJointPathInferenceTest {
    @TempDir Path temporary;

    @Test
    void sqlAndTwoBindingsSelectTheSameBranch() throws Exception {
        var sqls = analyze("""
                static void run(java.sql.Connection c, boolean flag) throws Exception {
                    String sql; int id; int age;
                    if (flag) { sql = "SELECT a WHERE id=? AND age=?"; id=1; age=10; }
                    else { sql = "SELECT b WHERE id=? AND age=?"; id=2; age=20; }
                    var p = c.prepareStatement(sql); p.setInt(1,id); p.setInt(2,age); p.executeQuery();
                }
                """);
        assertEquals(2, sqls.size());
        for (var sql : sqls) {
            var joint = sql.jointInference().orElseThrow();
            assertEquals(SATISFIABLE, joint.status(), joint.issues().toString());
            int id = sql.text().contains("SELECT a") ? 1 : 2;
            assertEquals(Set.of(List.of(id,id*10)), pairs(joint));
            assertTrue(joint.combinations().stream().allMatch(c -> !c.conditions().isEmpty()));
        }
    }

    @Test
    void literalStringConditionRejectsTheOppositeSqlBranch() throws Exception {
        var sqls = analyze("""
                public static void main(String[] args) throws Exception { run(null, "age"); }
                static void run(java.sql.Connection c, String sort) throws Exception {
                    String sql = "age".equals(sort) ? "SELECT age WHERE sort=?" : "SELECT id WHERE sort=?";
                    var p = c.prepareStatement(sql); p.setString(1, sort); p.executeQuery();
                }
                """);
        assertEquals(2, sqls.size());
        for (var sql : sqls) {
            var joint = sql.jointInference().orElseThrow();
            if (sql.text().startsWith("SELECT age")) {
                assertEquals(SATISFIABLE, joint.status(), joint.toString());
                assertEquals("age", known(only(only(joint.combinations()).bindings()).value()));
            } else {
                assertEquals(UNSATISFIABLE, joint.status(), joint.toString());
                assertTrue(joint.combinations().isEmpty());
            }
        }
    }

    @Test
    void externalStringConditionSuppliesTheBindingModel() throws Exception {
        var joint = only(analyze("""
                static void run(java.sql.Connection c, String sort) throws Exception {
                    if (!"age".equals(sort)) return;
                    var p = c.prepareStatement("SELECT ?"); p.setString(1, sort); p.executeQuery();
                }
                """)).jointInference().orElseThrow();
        assertEquals(SATISFIABLE, joint.status(), joint.toString());
        assertEquals("age", known(only(only(joint.combinations()).bindings()).value()));
    }

    @Test
    void repeatedPredicatesAndNumericRangesRejectConflictingPairs() throws Exception {
        var sql = only(analyze("""
                static void run(java.sql.Connection c, int n) throws Exception {
                    int first = n > 10 ? 11 : 12;
                    int second = n < 5 ? 21 : 22;
                    var p = c.prepareStatement("SELECT ?,?");
                    p.setInt(1,first); p.setInt(2,second); p.executeQuery();
                }
                """));
        var joint = sql.jointInference().orElseThrow();
        assertEquals(SATISFIABLE, joint.status(), joint.issues().toString());
        assertEquals(Set.of(List.of(11,22),List.of(12,21),List.of(12,22)), pairs(joint));
    }

    @Test
    void sharedInputModelReplaysSlotsAndBindingsTogether() throws Exception {
        var sql = only(analyze("""
                static void run(java.sql.Connection c, int n) throws Exception {
                    if(n < 100 || n > 100) return;
                    var p = c.prepareStatement("SELECT " + n + " WHERE a=? AND b=?");
                    p.setInt(1,n); p.setInt(2,n+1); p.executeQuery();
                }
                """));
        var joint = sql.jointInference().orElseThrow();
        assertEquals(SATISFIABLE, joint.status(), joint.issues().toString());
        var combination = only(joint.combinations());
        assertEquals(List.of(100,101), bound(combination));
        assertEquals("100", known(combination.parameters().get("n")));
        assertTrue(combination.inputs().values().contains(new SqlValue.Known(100)));
        assertThrows(UnsupportedOperationException.class, () -> combination.parameters().clear());
        assertThrows(UnsupportedOperationException.class, () -> combination.inputs().clear());
        assertThrows(UnsupportedOperationException.class, () -> joint.combinations().clear());
    }

    @Test
    void literalCallerMakesGuardedSqlUnreachable() throws Exception {
        var sql = only(analyze("""
                public static void main(String[] args) throws Exception { run(null,-1); }
                static void run(java.sql.Connection c, int n) throws Exception {
                    if(n < 0) return;
                    var p = c.prepareStatement("SELECT ?"); p.setInt(1,n); p.executeQuery();
                }
                """));
        var joint = sql.jointInference().orElseThrow();
        assertEquals(UNSATISFIABLE, joint.status(), joint.issues().toString());
        assertTrue(joint.combinations().isEmpty());
    }

    @Test
    void independentHelperCallsKeepIndependentConditions() throws Exception {
        var sql = only(analyze("""
                static int choose(boolean flag) { return flag ? 1 : 2; }
                static void run(java.sql.Connection c, boolean a, boolean b) throws Exception {
                    int left=choose(a), right=choose(b);
                    var p = c.prepareStatement("SELECT ?,?"); p.setInt(1,left); p.setInt(2,right); p.executeQuery();
                }
                """));
        var joint = sql.jointInference().orElseThrow();
        assertEquals(SATISFIABLE, joint.status(), joint.issues().toString());
        assertEquals(Set.of(List.of(1,1),List.of(1,2),List.of(2,1),List.of(2,2)), pairs(joint));
    }

    @Test
    void switchAndIfShareTheSameInputConstraint() throws Exception {
        var sql = only(analyze("""
                static void run(java.sql.Connection c, int n) throws Exception {
                    int a; switch(n) { case 1: a=10; break; case 2: a=20; break; default: a=30; }
                    int b = n == 1 ? 100 : 200;
                    var p = c.prepareStatement("SELECT ?,?"); p.setInt(1,a); p.setInt(2,b); p.executeQuery();
                }
                """));
        var joint = sql.jointInference().orElseThrow();
        assertEquals(SATISFIABLE, joint.status(), joint.issues().toString());
        assertEquals(Set.of(List.of(10,100),List.of(20,200),List.of(30,200)), pairs(joint));
    }

    @Test
    void nullPredicateSuppliesTheSameInputToReplay() throws Exception {
        var sql = only(analyze("""
                static void run(java.sql.Connection c, String name) throws Exception {
                    if(name != null) return;
                    var p = c.prepareStatement("SELECT ?"); p.setString(1,name); p.executeQuery();
                }
                """));
        var joint = sql.jointInference().orElseThrow();
        assertEquals(SATISFIABLE, joint.status(), joint.issues().toString());
        assertNull(known(only(joint.combinations()).bindings().getFirst().value()));
    }

    @Test
    void unsupportedConditionIsUnknownInsteadOfUnreachable() throws Exception {
        var sql = only(analyze("""
                static void run(java.sql.Connection c, String name) throws Exception {
                    int n = name.hashCode() > 10 ? 1 : 2;
                    var p = c.prepareStatement("SELECT ?"); p.setInt(1,n); p.executeQuery();
                }
                """));
        var joint = sql.jointInference().orElseThrow();
        assertEquals(UNKNOWN, joint.status());
        assertEquals(Set.of(1,2), joint.combinations().stream().map(c -> known(c.bindings().getFirst().value())).collect(Collectors.toSet()));
        assertTrue(joint.combinations().stream().allMatch(c -> c.status() == UNKNOWN && !c.issues().isEmpty()));
    }

    @Test
    void dynamicBindingIndexAndValueChooseTheSameBranch() throws Exception {
        var sql = only(analyze("""
                static void run(java.sql.Connection c, boolean flag) throws Exception {
                    int index = flag ? 1 : 2;
                    int value = flag ? 10 : 20;
                    var p = c.prepareStatement("SELECT ?,?"); p.setInt(index,value); p.executeQuery();
                }
                """));
        var joint = sql.jointInference().orElseThrow();
        assertEquals(UNKNOWN, joint.status(), "JDBC 动态下标覆盖语义尚有诊断，不能声称完整求解");
        assertEquals(Set.of(List.of(1,10),List.of(2,20)), joint.combinations().stream().map(c -> {
            var binding = only(c.bindings());
            return List.of(((Number) known(binding.keyValue())).intValue(),known(binding.value()));
        }).collect(Collectors.toSet()));
    }

    @Test
    void longBoundaryModelPreservesJavaOverflowDuringReplay() throws Exception {
        var sql = only(analyze("""
                static void run(java.sql.Connection c, long n) throws Exception {
                    if(n != Long.MAX_VALUE) return;
                    var p = c.prepareStatement("SELECT ?,?"); p.setLong(1,n); p.setLong(2,n+1); p.executeQuery();
                }
                """));
        var joint = sql.jointInference().orElseThrow();
        assertEquals(SATISFIABLE, joint.status(), joint.issues().toString());
        assertEquals(Set.of(List.of(Long.MAX_VALUE,Long.MIN_VALUE)),pairs(joint));
    }

    @Test
    void loopsKeepAnExplicitUnknownCandidate() throws Exception {
        var result = analyzeResult("""
                static void run(java.sql.Connection c, boolean wait) throws Exception {
                    while(wait) { Thread.yield(); }
                    var p = c.prepareStatement("SELECT ?"); p.setInt(1,1); p.executeQuery();
                }
                """);
        assertEquals(AnalysisResult.Status.PARTIAL, result.status());
        var sql = only(executedSqls(result));
        var joint = sql.jointInference().orElseThrow();
        assertEquals(UNKNOWN,joint.status());
        assertFalse(joint.coverageComplete());
        assertFalse(joint.combinations().isEmpty());
        assertTrue(joint.issues().stream().anyMatch(issue -> issue.contains("loop")));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("JOINT_ANALYSIS_INCOMPLETE")
                && d.incomplete() && d.stage() == AnalysisListener.Stage.ANALYZING && d.location().isPresent()
                && d.location().get().equals(result.extractions().stream()
                .filter(e -> e.callSite().target().getName().equals("executeQuery")).findFirst().orElseThrow().location())));
    }

    @Test
    void combinationBudgetIsReportedRatherThanClaimingNoSolution() throws Exception {
        StringBuilder method = new StringBuilder("static void run(java.sql.Connection c");
        for (int i=1;i<=10;i++) method.append(",boolean flag").append(i);
        method.append(") throws Exception {");
        // 独立条件确实有 2^10 种组合；同一 flag 的十次引用应由前缀剪枝收敛为两种。
        for (int i=1;i<=10;i++) method.append("int v").append(i).append("=flag").append(i).append("?1:2;");
        method.append("var p=c.prepareStatement(\"SELECT ?,?,?,?,?,?,?,?,?,?\");");
        for (int i=1;i<=10;i++) method.append("p.setInt(").append(i).append(",v").append(i).append(");");
        method.append("p.executeQuery();}");
        var joint = only(analyze(method.toString())).jointInference().orElseThrow();
        assertEquals(UNKNOWN,joint.status());
        assertFalse(joint.coverageComplete());
        assertTrue(joint.issues().stream().anyMatch(issue -> issue.contains("combination limit")));
    }

    @Test
    void laterSetterCannotBeSkippedUnderTheSameCondition() throws Exception {
        var sqls = analyze("""
                static void run(java.sql.Connection c, boolean flag) throws Exception {
                    var p = c.prepareStatement("SELECT ?");
                    if(flag) p.setInt(1,1);
                    if(flag) p.setInt(1,2);
                    p.executeQuery();
                }
                """);
        assertEquals(Set.of(2), sqls.stream().flatMap(sql -> sql.jointInference().orElseThrow().combinations().stream())
                .flatMap(c -> c.bindings().stream()).map(b -> known(b.value())).collect(Collectors.toSet()));
        assertTrue(sqls.stream().anyMatch(sql -> sql.jointInference().orElseThrow().status() == UNSATISFIABLE));
    }

    @Test
    void clearParametersUsesTheSnapshotPathCondition() throws Exception {
        var sqls = analyze("""
                static void run(java.sql.Connection c, boolean flag) throws Exception {
                    var p = c.prepareStatement("SELECT ?"); p.setInt(1,0);
                    if(flag) p.setInt(1,1);
                    if(flag) p.clearParameters();
                    p.executeQuery();
                }
                """);
        assertEquals(Set.of(0), sqls.stream().flatMap(sql -> sql.jointInference().orElseThrow().combinations().stream())
                .flatMap(c -> c.bindings().stream()).map(b -> known(b.value())).collect(Collectors.toSet()));
        assertTrue(sqls.stream().flatMap(sql -> sql.jointInference().orElseThrow().combinations().stream())
                .anyMatch(c -> c.status() == SATISFIABLE && c.bindings().isEmpty()));
    }

    @Test
    void batchEntriesRetainTheirOwnGuardAndBindingSnapshot() throws Exception {
        var sqls = analyze("""
                static void run(java.sql.Connection c, boolean flag) throws Exception {
                    var p = c.prepareStatement("SELECT ?");
                    p.setInt(1,10); if(flag) p.addBatch();
                    p.setInt(1,20); if(!flag) p.addBatch();
                    p.executeBatch();
                }
                """);
        Set<Object> values = new HashSet<>();
        for (var sql : sqls) for (var combination : sql.jointInference().orElseThrow().combinations()) {
            assertEquals(SATISFIABLE,combination.status(),combination.issues().toString());
            Object value = known(only(combination.bindings()).value()); values.add(value);
            assertTrue(combination.inputs().values().contains(new SqlValue.Known(value.equals(10))));
        }
        assertEquals(Set.of(10,20),values);
    }

    @Test
    void exceptionalExitThatCannotReachSqlDoesNotInvalidateTheNormalModel() throws Exception {
        var sql = only(analyze("""
                static void run(java.sql.Connection c) throws Exception {
                    try {
                        var p = c.prepareStatement("SELECT ?"); p.setInt(1,5); p.executeQuery();
                    } finally { System.out.println("finished"); }
                }
                """));
        var joint = sql.jointInference().orElseThrow();
        assertEquals(SATISFIABLE,joint.status(),joint.issues().toString());
        assertEquals(5,known(only(joint.combinations()).bindings().getFirst().value()));
    }

    @Test
    void jointBudgetsAreHonoredFromAnalyzerConfiguration() throws Exception {
        Path source = Files.writeString(temporary.resolve("Sample.java"), """
                public class Sample {
                    static void run(java.sql.Connection c, boolean f1, boolean f2) throws Exception {
                        var p = c.prepareStatement("SELECT ?,?");
                        int a = f1 ? 1 : 2; int b = f2 ? 3 : 4;
                        p.setInt(1, a); p.setInt(2, b); p.executeQuery();
                    }
                }
                """);
        Path classes = Files.createDirectory(temporary.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-g","-d",classes.toString(),source.toString()));
        // 两个独立条件有 4 条互不矛盾的可行路径；每帧只保留 1 条时，探索必须截断并报告，
        // 证明 framePaths 确实来自 AnalyzerConfig 而不是硬编码。
        var config = AnalyzerConfig.defaults().withMaxTraceDepth(64)
                .withJointBudgets(JointBudgets.defaults().withFramePaths(1));
        var result = new Analyzer(config, List.of(new JdbcAnalysisModule()),List.of()).analyze(classes);
        assertNotEquals(AnalysisResult.Status.FAILED,result.status());
        var joint = only(executedSqls(result)).jointInference().orElseThrow();
        assertTrue(joint.issues().stream().anyMatch(issue -> issue.contains("control-flow exploration limit")),
                joint.issues().toString());
    }

    private List<RawSql.Variant> analyze(String methods) throws Exception {
        return executedSqls(analyzeResult(methods));
    }
    private AnalysisResult analyzeResult(String methods) throws Exception {
        Path source = Files.writeString(temporary.resolve("Sample.java"), "public class Sample {"+methods+"}");
        Path classes = Files.createDirectory(temporary.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-g","-d",classes.toString(),source.toString()));
        var result = new Analyzer(AnalyzerConfig.defaults().withMaxTraceDepth(64), List.of(new JdbcAnalysisModule()),List.of()).analyze(classes);
        assertNotEquals(AnalysisResult.Status.FAILED,result.status());
        return result;
    }
    private List<RawSql.Variant> executedSqls(AnalysisResult result) {
        return result.extractions().stream().filter(e -> Set.of("executeQuery","executeBatch").contains(e.callSite().target().getName()))
                .flatMap(e -> e.statements().stream()).flatMap(s -> s.rawSql().variants().stream()).toList();
    }
    private static Set<List<Object>> pairs(JointSqlInferenceResult joint) {
        return joint.combinations().stream().map(JdbcJointPathInferenceTest::bound).collect(Collectors.toSet());
    }
    private static List<Object> bound(JointSqlInferenceResult.Combination combination) {
        return combination.bindings().stream().sorted(Comparator.comparing(JointSqlInferenceResult.BindingValue::key))
                .map(b -> known(b.value())).toList();
    }
    private static Object known(InferredParameterValue value) { return assertInstanceOf(SqlValue.Known.class,value.value()).value(); }
    private static <T> T only(List<T> values) { assertEquals(1,values.size()); return values.getFirst(); }
}
