package org.u80dc.jsqlint.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.parameter.*;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult.Status.*;

/** 条件依赖闭包与安全剪枝：同时验证保留的值、路径约束和未知边界。 @author u80dc */
class JdbcJointPathOptimizationTest {
    @TempDir Path temporary;

    @Test
    void predicateHelperSuppliesItsInputConstraintToBindingReplay() throws Exception {
        var joint = only(analyze("""
                static boolean valid(int n) { return n > 0; }
                static void run(java.sql.Connection c,int n) throws Exception {
                    if(!valid(n))return;
                    var p=c.prepareStatement("SELECT ?");p.setInt(1,n);p.executeQuery();
                }
                """)).jointInference().orElseThrow();
        assertEquals(SATISFIABLE,joint.status(),joint.issues().toString());
        assertFalse(joint.combinations().isEmpty());
        assertTrue(values(joint).stream().allMatch(n -> (Integer)n > 0));
    }

    @Test
    void nestedHelpersAndMultipleReturnsAreAlternatives() throws Exception {
        var joint = only(analyze("""
                static boolean valid(int n) { return first(n); }
                static boolean first(int n) { if(n==1)return true; return second(n); }
                static boolean second(int n) { return n==2; }
                static void run(java.sql.Connection c,int n) throws Exception {
                    if(!valid(n))return;
                    var p=c.prepareStatement("SELECT ?");p.setInt(1,n);p.executeQuery();
                }
                """)).jointInference().orElseThrow();
        assertEquals(SATISFIABLE,joint.status(),joint.issues().toString());
        assertEquals(Set.of(1,2),values(joint));
    }

    @Test
    void helperGuardContradictsTheLiteralCaller() throws Exception {
        var joint = only(analyze("""
                public static void main(String[] args)throws Exception{run(null,-1);}
                static boolean valid(int n){return n>0;}
                static void run(java.sql.Statement s,int n)throws Exception{
                    if(valid(n))s.execute("SELECT 1");
                }
                """)).jointInference().orElseThrow();
        assertEquals(UNSATISFIABLE,joint.status(),joint.issues().toString());
        assertTrue(joint.combinations().isEmpty());
    }

    @Test
    void separatePredicateInvocationsKeepTheirOwnArguments() throws Exception {
        var joint = only(analyze("""
                static boolean valid(int n){return n>0;}
                static void run(java.sql.Connection c,int a,int b)throws Exception{
                    if(!valid(a)||valid(b))return;
                    var p=c.prepareStatement("SELECT ?,?");p.setInt(1,a);p.setInt(2,b);p.executeQuery();
                }
                """)).jointInference().orElseThrow();
        assertEquals(SATISFIABLE,joint.status(),joint.issues().toString());
        assertFalse(joint.combinations().isEmpty());
        for(var combination:joint.combinations()) {
            var bindings=combination.bindings().stream().collect(Collectors.toMap(JointSqlInferenceResult.BindingValue::key,b -> known(b.value())));
            assertTrue((Integer)bindings.get("1")>0);
            assertTrue((Integer)bindings.get("2")<=0);
        }
    }

    @Test
    void switchOnHelperReturnIncludesTheSelectedCalleeBranch() throws Exception {
        var joint=only(analyze("""
                static int kind(int n){if(n<0)return -1;if(n==0)return 0;return 1;}
                static void run(java.sql.Connection c,int n)throws Exception{
                    switch(kind(n)){case 1:break;default:return;}
                    var p=c.prepareStatement("SELECT ?");p.setInt(1,n);p.executeQuery();
                }
                """)).jointInference().orElseThrow();
        assertEquals(SATISFIABLE,joint.status(),joint.issues().toString());
        assertFalse(values(joint).isEmpty());
        assertTrue(values(joint).stream().allMatch(n -> (Integer)n>0));
    }

    @Test
    void assignmentsFeedingPredicateArgumentsAreRelevantEvenForConstantSql() throws Exception {
        var joint=only(analyze("""
                static boolean valid(int n){return n>0;}
                static void run(java.sql.Statement s,boolean flag)throws Exception{
                    int value=1;if(flag)value=-1;
                    if(valid(value))s.execute("SELECT 1");
                }
                """)).jointInference().orElseThrow();
        assertEquals(SATISFIABLE,joint.status(),joint.issues().toString());
        assertFalse(joint.combinations().isEmpty());
        assertTrue(joint.combinations().stream().allMatch(c -> c.inputs().values().contains(new SqlValue.Known(false))
                && !c.inputs().values().contains(new SqlValue.Known(true))));
    }

    @Test
    void virtualPredicateTargetsAreNotConjoined() throws Exception {
        var joint = only(analyze("""
                interface Check { boolean valid(int n); }
                static class Positive implements Check { public boolean valid(int n){return n>0;} }
                static class Negative implements Check { public boolean valid(int n){return n<0;} }
                static void run(java.sql.Connection c,Check check,int n)throws Exception{
                    if(!check.valid(n))return;
                    var p=c.prepareStatement("SELECT ?");p.setInt(1,n);p.executeQuery();
                }
                """)).jointInference().orElseThrow();
        assertEquals(SATISFIABLE,joint.status(),joint.issues().toString());
        assertTrue(values(joint).stream().anyMatch(n -> (Integer)n>0));
        assertTrue(values(joint).stream().anyMatch(n -> (Integer)n<0));
    }

    @Test
    void openPredicateDispatchRetainsBothApplicationAndUnknownAlternatives() throws Exception {
        var joint=only(analyze("""
                static class Positive implements java.util.function.IntPredicate {
                    public boolean test(int n){return n>0;}
                }
                static void run(java.sql.Connection c,java.util.function.IntPredicate predicate,int n)throws Exception{
                    if(!predicate.test(n))return;
                    var p=c.prepareStatement("SELECT ?");p.setInt(1,n);p.executeQuery();
                }
                """)).jointInference().orElseThrow();
        assertEquals(UNKNOWN,joint.status());
        assertTrue(joint.combinations().stream().anyMatch(c -> c.status()==UNKNOWN));
        var known=joint.combinations().stream().filter(c -> c.status()==SATISFIABLE).toList();
        assertFalse(known.isEmpty());
        assertTrue(known.stream().allMatch(c -> (Integer)known(only(c.bindings()).value())>0));
    }

    @Test
    void loopInsidePredicateRetainsAnUnknownCandidate() throws Exception {
        var joint=only(analyze("""
                static boolean valid(int n){while(n<0)n++;return n>0;}
                static void run(java.sql.Statement s,int n)throws Exception{
                    if(valid(n))s.execute("SELECT 1");
                }
                """)).jointInference().orElseThrow();
        assertEquals(UNKNOWN,joint.status());
        assertTrue(joint.issues().stream().anyMatch(issue -> issue.contains("loop")));
        assertTrue(joint.combinations().stream().anyMatch(c -> c.status()==UNKNOWN));
    }

    @Test
    void unsupportedOperationInsidePredicateRemainsUnknown() throws Exception {
        var joint = only(analyze("""
                static boolean valid(String name){return name.hashCode()>0;}
                static void run(java.sql.Statement s,String name)throws Exception{
                    if(valid(name))s.execute("SELECT 1");
                }
                """)).jointInference().orElseThrow();
        assertEquals(UNKNOWN,joint.status());
        assertFalse(joint.combinations().isEmpty());
        assertTrue(joint.combinations().stream().allMatch(c -> c.status()==UNKNOWN));
    }

    @Test
    void recursivePredicateKeepsTheCallBudgetDiagnostic() throws Exception {
        var joint = only(analyze("""
                static boolean valid(int n){return valid(n);}
                static void run(java.sql.Statement s,int n)throws Exception{
                    if(valid(n))s.execute("SELECT 1");
                }
                """, AnalyzerConfig.defaults().withMaxCallDepth(2))).jointInference().orElseThrow();
        assertEquals(UNKNOWN,joint.status());
        assertFalse(joint.combinations().isEmpty());
    }

    @Test
    void unrelatedDiamondsDoNotConsumeThePathBudget() throws Exception {
        var sql = only(analyze(noiseMethod(false,12)));
        var joint=sql.jointInference().orElseThrow();
        assertEquals(SATISFIABLE,joint.status(),joint.issues().toString());
        assertEquals(1,joint.combinations().size());
        assertTrue(joint.combinations().getFirst().conditions().isEmpty());
    }

    @Test
    void equivalentPreparedHistoriesCollapseWithoutLosingTheBinding() throws Exception {
        var joint=only(analyze(noiseMethod(true,8))).jointInference().orElseThrow();
        assertEquals(SATISFIABLE,joint.status(),joint.issues().toString());
        assertEquals(1,joint.combinations().size());
        assertEquals(Set.of(7),values(joint));
    }

    @Test
    void earlyReturnAndParameterAssignmentsAreNeverPrunedAsNoise() throws Exception {
        var joint=only(analyze("""
                static void run(java.sql.Connection c,boolean stop,boolean flag)throws Exception{
                    if(stop)return;
                    int n=0;if(flag)n=1;
                    var p=c.prepareStatement("SELECT ?");p.setInt(1,n);p.executeQuery();
                }
                """)).jointInference().orElseThrow();
        assertEquals(SATISFIABLE,joint.status());
        assertEquals(Set.of(0,1),values(joint));
        assertTrue(joint.combinations().stream().allMatch(c -> !c.conditions().isEmpty()));
    }

    @Test
    void pruningAnEarlierDiamondCannotRemoveALaterGuardOnTheSameInput() throws Exception {
        var joint=only(analyze("""
                public static void main(String[] args)throws Exception{run(null,true);}
                static void run(java.sql.Statement s,boolean stop)throws Exception{
                    if(stop)System.out.println("noise");
                    if(stop)return;
                    s.execute("SELECT 1");
                }
                """)).jointInference().orElseThrow();
        assertEquals(UNSATISFIABLE,joint.status());
    }

    @Test
    void partialSnapshotHistoryCoverageDoesNotEraseTheRequiredGuard() throws Exception {
        var sqls=analyze("""
                static void run(java.sql.Connection c,boolean a,boolean b)throws Exception{
                    var p=c.prepareStatement("SELECT ?");p.setInt(1,0);
                    if(a){if(b)p.setInt(1,1);}else{p.setInt(1,1);}
                    if(a&&b)return;
                    p.executeQuery();
                }
                """);
        assertEquals(Set.of(0,1),sqls.stream().flatMap(sql -> values(sql.jointInference().orElseThrow()).stream()).collect(Collectors.toSet()));
        for(var sql:sqls)for(var combination:sql.jointInference().orElseThrow().combinations()) {
            assertEquals(SATISFIABLE,combination.status(),combination.issues().toString());
            if(known(only(combination.bindings()).value()).equals(0))
                assertTrue(combination.inputs().values().contains(new SqlValue.Known(true))
                        && combination.inputs().values().contains(new SqlValue.Known(false)));
        }
    }

    @Test
    void correlatedParametersArePrunedBeforeTheirCartesianProduct() throws Exception {
        StringBuilder method=new StringBuilder("static void run(java.sql.Connection c,boolean flag)throws Exception{");
        for(int i=1;i<=10;i++)method.append("int v").append(i).append("=flag?1:2;");
        method.append("var p=c.prepareStatement(\"SELECT ?,?,?,?,?,?,?,?,?,?\");");
        for(int i=1;i<=10;i++)method.append("p.setInt(").append(i).append(",v").append(i).append(");");
        var joint=only(analyze(method.append("p.executeQuery();}").toString())).jointInference().orElseThrow();
        assertEquals(SATISFIABLE,joint.status(),joint.issues().toString());
        assertEquals(2,joint.combinations().size());
        assertEquals(Set.of(1,2),values(joint));
        for(var combination:joint.combinations())assertEquals(1,combination.bindings().stream()
                .map(b -> known(b.value())).distinct().count());
    }

    private String noiseMethod(boolean prepared,int count) {
        StringBuilder source=new StringBuilder("static void run(java.sql.Connection c,java.sql.Statement s");
        for(int i=0;i<count;i++)source.append(",boolean f").append(i);
        source.append(")throws Exception{");
        if(prepared)source.append("var p=c.prepareStatement(\"SELECT ?\");p.setInt(1,7);");
        for(int i=0;i<count;i++)source.append("if(f").append(i).append(")System.out.println(").append(i).append(");");
        return source.append(prepared?"p.executeQuery();}":"s.execute(\"SELECT 1\");}").toString();
    }
    private List<RawSql.Variant> analyze(String methods)throws Exception{return analyze(methods,AnalyzerConfig.defaults());}
    private List<RawSql.Variant> analyze(String methods,AnalyzerConfig config)throws Exception{
        Path root=Files.createTempDirectory(temporary,"case-");
        Path source=Files.writeString(root.resolve("Sample.java"),"public class Sample{"+methods+"}");
        Path classes=Files.createDirectory(root.resolve("classes"));
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-g","-d",classes.toString(),source.toString()));
        var result=new Analyzer(config,List.of(new JdbcAnalysisModule()),List.of()).analyze(classes);
        assertNotEquals(AnalysisResult.Status.FAILED,result.status());
        return result.calls().stream().filter(e -> Set.of("execute","executeQuery").contains(e.callSite().target().getName()))
                .flatMap(e -> e.statements().stream()).flatMap(s -> s.rawSql().variants().stream()).toList();
    }
    private static Set<Object> values(JointSqlInferenceResult joint){return joint.combinations().stream()
            .flatMap(c -> c.bindings().stream()).map(b -> known(b.value())).collect(Collectors.toSet());}
    private static Object known(InferredParameterValue value){return assertInstanceOf(SqlValue.Known.class,value.value()).value();}
    private static <T>T only(List<T> values){assertEquals(1,values.size());return values.getFirst();}
}
