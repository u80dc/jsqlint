package org.u80dc.jsqlint.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.parameter.*;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult.Status.*;

/** 同次调用的分派选择，以及外部数组与元素的联合模型必须一致。 @author u80dc */
class JdbcJointConsistencyTest {
    @TempDir Path temporary;
    private static final String VALUES = """
            interface Value { int get(); }
            static class One implements Value { public int get(){return 1;} }
            static class Two implements Value { public int get(){return 2;} }
            """;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repeatedReturnUsesOneDispatchTargetIncludingNestedCalls(boolean nested) throws Exception {
        var joint=only(analyze(VALUES + "static int read(Value v){return v.get();}" + """
                static void run(java.sql.Connection c,Value v)throws Exception{
                    int n=%s;
                    var p=c.prepareStatement("SELECT ?,?");p.setInt(1,n);p.setInt(2,n);p.executeQuery();
                }
                """.formatted(nested ? "read(v)" : "v.get()"))).jointInference().orElseThrow();
        assertEquals(SATISFIABLE,joint.status(),joint.issues().toString());
        assertEquals(Set.of(List.of(1,1),List.of(2,2)),pairs(joint));
    }

    @Test
    void guardAndBindingsShareTheSameDispatchTarget() throws Exception {
        var joint=only(analyze(VALUES + """
                static void run(java.sql.Connection c,Value v)throws Exception{
                    int n=v.get();if(n!=1)return;
                    var p=c.prepareStatement("SELECT ?,?");p.setInt(1,n);p.setInt(2,n);p.executeQuery();
                }
                """)).jointInference().orElseThrow();
        assertEquals(SATISFIABLE,joint.status(),joint.issues().toString());
        assertEquals(Set.of(List.of(1,1)),pairs(joint));
    }

    @Test
    void sqlTextAndBindingShareTheSameDispatchTarget() throws Exception {
        var sqls=analyze(VALUES + """
                static void run(java.sql.Connection c,Value v)throws Exception{
                    int n=v.get();
                    var p=c.prepareStatement("SELECT "+n+", ?");p.setInt(1,n);p.executeQuery();
                }
                """);
        assertEquals(2,sqls.size());
        for(var sql:sqls){
            var joint=sql.jointInference().orElseThrow();
            assertEquals(SATISFIABLE,joint.status(),joint.issues().toString());
            for(var combination:joint.combinations())
                assertEquals("SELECT "+known(only(combination.bindings()).value())+", ?",sql.text());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void distinctInvocationsKeepIndependentTargets(boolean nested) throws Exception {
        var joint=only(analyze(VALUES + "static int read(Value v){return v.get();}" + """
                static void run(java.sql.Connection c,Value a,Value b)throws Exception{
                    int x=%s,y=%s;
                    var p=c.prepareStatement("SELECT ?,?");p.setInt(1,x);p.setInt(2,y);p.executeQuery();
                }
                """.formatted(nested ? "read(a)" : "a.get()", nested ? "read(b)" : "b.get()")))
                .jointInference().orElseThrow();
        assertEquals(SATISFIABLE,joint.status(),joint.issues().toString());
        assertEquals(Set.of(List.of(1,1),List.of(1,2),List.of(2,1),List.of(2,2)),pairs(joint));
    }

    @Test
    void externalAndApplicationReturnsOfOneInvocationAreExclusive() throws Exception {
        var joint=only(analyze("""
                static class One implements java.util.function.IntSupplier {public int getAsInt(){return 1;}}
                static void run(java.sql.Connection c,java.util.function.IntSupplier v)throws Exception{
                    int n=v.getAsInt();
                    var p=c.prepareStatement("SELECT ?,?");p.setInt(1,n);p.setInt(2,n);p.executeQuery();
                }
                """)).jointInference().orElseThrow();
        assertEquals(UNKNOWN,joint.status());
        assertTrue(joint.combinations().stream().anyMatch(c -> c.status()==SATISFIABLE));
        assertTrue(joint.combinations().stream().anyMatch(c -> c.status()==UNKNOWN));
        for(var combination:joint.combinations()) {
            long known=combination.bindings().stream().filter(b -> b.value().value() instanceof SqlValue.Known).count();
            assertTrue(known==0 || known==2,combination.toString());
        }
    }

    @Test
    void applicationTargetReturningAnUnknownLibraryValueIsNotDiscarded() throws Exception {
        var joint=only(analyze("""
                interface Value {int get();}
                static class External implements Value {public int get(){return System.identityHashCode(this);}}
                static void run(java.sql.Connection c,Value v)throws Exception{
                    int n=v.get();var p=c.prepareStatement("SELECT ?");p.setInt(1,n);p.executeQuery();
                }
                """)).jointInference().orElseThrow();
        assertEquals(UNKNOWN,joint.status());
        assertFalse(joint.combinations().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"binding", "slot", "forward"})
    void nullArrayModelCannotProduceASatisfiableElement(String use) throws Exception {
        String query=switch(use){
            case "slot" -> "s.executeQuery(\"SELECT '\"+other[0]+\"'\");";
            case "forward" -> "query(c,other[0]);";
            default -> "var p=c.prepareStatement(\"SELECT ?\");p.setString(1,other[0]);p.executeQuery();";
        };
        var sqls=analyze("""
                static void run(java.sql.Connection c,java.sql.Statement s,String[] args)throws Exception{
                    if(args!=null)return;
                    String[] other=args;%s
                }
                """.formatted(query) + (use.equals("forward") ? """
                static void query(java.sql.Connection c,String value)throws Exception{
                    var p=c.prepareStatement("SELECT ?");p.setString(1,value);p.executeQuery();
                }
                """ : ""));
        assertFalse(sqls.isEmpty());
        for(var sql:sqls){
            var joint=sql.jointInference().orElseThrow();
            assertNotEquals(SATISFIABLE,joint.status());
            assertTrue(joint.combinations().stream().noneMatch(c -> c.status()==SATISFIABLE));
            assertTrue(joint.combinations().stream().flatMap(c -> c.issues().stream())
                    .anyMatch(issue -> issue.toLowerCase(Locale.ROOT).contains("array") && issue.contains("null")));
        }
    }

    @Test
    void unguardedArrayAliasesStillReplayTheSameElement() throws Exception {
        var joint=only(analyze("""
                static void run(java.sql.Connection c,String[] args)throws Exception{
                    String[] other=args;
                    var p=c.prepareStatement("SELECT ?,?");p.setString(1,args[0]);p.setString(2,other[0]);p.executeQuery();
                }
                """)).jointInference().orElseThrow();
        assertEquals(SATISFIABLE,joint.status(),joint.issues().toString());
        var values=only(pairs(joint).stream().toList());
        assertEquals(values.getFirst(),values.get(1));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nullModelIsCheckedOnBothOriginalAndSavedArrayTraces(boolean saved) throws Exception {
        var trace=arrayTrace();
        var base=input(trace,"java.lang.String[]");
        if(saved)trace=only(replay(trace,Map.of()).possibleValues()).propagationPath();
        var result=replay(trace,Map.of(base,new SqlValue.Known(null)));
        assertFalse(result.complete());
        assertInstanceOf(SqlValue.Unknown.class,only(result.possibleValues()).value());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("null array")));
    }

    @Test
    void arrayModelSuppliesActualValuesAndChecksItsBounds() throws Exception {
        var trace=arrayTrace();
        var base=input(trace,"java.lang.String[]");
        var index=input(trace,"int");
        var model=new HashMap<ValueTrace.Variable,SqlValue>();
        model.put(base,new SqlValue.Known(new String[]{"first",null}));
        model.put(index,new SqlValue.Known(0));
        var first=only(replay(trace,model).possibleValues());
        assertEquals("first",known(first));
        model.put(index,new SqlValue.Known(1));
        var second=only(replay(first.propagationPath(),model).possibleValues());
        assertNull(known(second));
        assertTrue(second.initialValues().stream().anyMatch(v -> v.variable().expression().endsWith("[1]") && !v.generated()));
        model.put(index,new SqlValue.Known(2));
        var outOfBounds=replay(second.propagationPath(),model);
        assertFalse(outOfBounds.complete());
        assertTrue(outOfBounds.issues().stream().anyMatch(issue -> issue.contains("model length")));
    }

    @Test
    void savedArrayTraceUsesTheNewIndexAndElementModelWithoutGrowing() throws Exception {
        var trace=arrayTrace();
        var index=input(trace,"int");
        var first=only(replay(trace,Map.of(index,new SqlValue.Known(0))).possibleValues());
        var second=only(replay(first.propagationPath(),Map.of(index,new SqlValue.Known(1))).possibleValues());
        var element=only(second.initialValues().stream().filter(InferredParameterValue.InitialValue::generated)
                .filter(v -> v.variable().type().equals("java.lang.String")).toList()).variable();
        assertTrue(element.expression().endsWith("[1]"));
        var model=Map.of(index,new SqlValue.Known(1),element,new SqlValue.Known("selected"));
        ValueTrace saved=second.propagationPath();
        for(int i=0;i<3;i++){
            var replayed=only(replay(saved,model).possibleValues());
            assertEquals("selected",known(replayed));
            assertEquals(saved,replayed.propagationPath());
            saved=replayed.propagationPath();
        }
    }

    @Test
    void unknownOrInconsistentArrayModelsNeverGenerateKnownElements() throws Exception {
        var trace=arrayTrace();
        var base=input(trace,"java.lang.String[]");
        var index=input(trace,"int");
        var sample=only(replay(trace,Map.of(index,new SqlValue.Known(0))).possibleValues());
        var element=only(sample.initialValues().stream().filter(v -> v.generated() && v.variable().type().equals("java.lang.String")).toList()).variable();
        for(SqlValue array:List.of(new SqlValue.Unknown("missing array"),new SqlValue.Known("wrong type"),new SqlValue.Known(new String[]{"actual"}))){
            var result=replay(sample.propagationPath(),Map.of(base,array,index,new SqlValue.Known(0),element,new SqlValue.Known("conflicting")));
            assertFalse(result.complete());
            assertInstanceOf(SqlValue.Unknown.class,only(result.possibleValues()).value());
        }
    }

    private ValueTrace arrayTrace()throws Exception{
        return only(only(analyze("""
                static void run(java.sql.Connection c,String[] args,int index)throws Exception{
                    var p=c.prepareStatement("SELECT ?");p.setString(1,args[index]);p.executeQuery();
                }
                """)).bindings()).value();
    }
    private static ValueTrace.Variable input(ValueTrace trace,String type){
        Deque<ValueTrace.Node> work=new ArrayDeque<>();work.add(trace.root());
        while(!work.isEmpty()){
            var node=work.removeFirst();
            if(node.inputs().isEmpty() && node.variable().type().equals(type)
                    && Set.of(ValueTrace.Kind.ENTRY,ValueTrace.Kind.NO_CALLER).contains(node.kind()))return node.variable();
            work.addAll(node.inputs());
        }
        throw new AssertionError("Missing input: "+type);
    }
    private static ParameterValueInferenceResult replay(ValueTrace trace,Map<ValueTrace.Variable,? extends SqlValue> inputs){
        return new ParameterValueInferencer((type,name) -> new SqlValue.Known(type.equals("int")?0:"sample"),()->{},new HashMap<>(inputs)).infer(trace);
    }

    private List<RawSql.Variant> analyze(String methods)throws Exception{
        Path root=Files.createTempDirectory(temporary,"case-");
        Path source=Files.writeString(root.resolve("Sample.java"),"public class Sample{"+methods+"}");
        Path classes=Files.createDirectory(root.resolve("classes"));
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-g","-d",classes.toString(),source.toString()));
        var result=new Analyzer(AnalyzerConfig.defaults(),List.of(new JdbcAnalysisModule()),List.of()).analyze(classes);
        assertNotEquals(AnalysisResult.Status.FAILED,result.status());
        return result.calls().stream().filter(e -> e.callSite().target().getName().equals("executeQuery"))
                .flatMap(e -> e.statements().stream()).flatMap(s -> s.rawSql().variants().stream()).toList();
    }
    private static Set<List<Object>> pairs(JointSqlInferenceResult joint){return joint.combinations().stream().map(c -> c.bindings().stream()
            .sorted(Comparator.comparing(JointSqlInferenceResult.BindingValue::key)).map(b -> known(b.value())).toList()).collect(Collectors.toSet());}
    private static Object known(InferredParameterValue value){return assertInstanceOf(SqlValue.Known.class,value.value()).value();}
    private static <T>T only(List<T> values){assertEquals(1,values.size());return values.getFirst();}
}
