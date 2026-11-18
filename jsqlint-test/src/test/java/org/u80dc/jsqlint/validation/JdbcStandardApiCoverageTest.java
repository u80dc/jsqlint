package org.u80dc.jsqlint.validation;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.objectweb.asm.*;
import org.u80dc.jsqlint.commons.location.*;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.spi.SqlCallSite;

import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.validation.SampleSqlCoverageTest.canonical;
import static org.u80dc.jsqlint.validation.SampleSqlCoverageTest.root;

/** 第二批标准 API、命名绑定和状态边界：原项目回放、手写基准、Analyzer 输出三方对照。 */
@Isolated("共享 Maven 编译器及 JDBC 驱动加载状态")
class JdbcStandardApiCoverageTest {
    @TempDir Path temporary;
    private final List<String> evidence=new ArrayList<>();
    private final List<String> metrics=new ArrayList<>();

    @Test @Tag("integration")
    void comparesMissingStandardApisAndStateTransitionsWithIndependentOracles() throws Exception {
        try (var analyzed=SampleSqlCoverageTest.analyzeProject(temporary,"jdbc-standard-api",null)) {
            try {
                var inventory=JdbcCallSiteInventory.compare(analyzed.input().getClassOutputDirectory(),analyzed.result());
                metric("all_code_sql_sites",inventory.total(),inventory.matched(),inventory.extra(),inventory.missing());
                inventory.evidence().forEach(value -> evidence.add("CODE_SITE " + value));
                signatureInventory(analyzed.input().getClassOutputDirectory());
                var reference=references();
                var recorder=new Recorder();
                for (var entry:reference.entrySet()) {
                    Class<?> scenarios=analyzed.loader().loadClass("org.u80dc.jsqlint.samples.jdbcapi."
                            + (entry.getKey().equals("verifyRows") || STATE_METHODS.contains(entry.getKey()) ? "JdbcStateScenarios" : "JdbcApiScenarios"));
                    recorder.runs.clear();
                    scenarios.getMethod(entry.getKey(),Connection.class).invoke(null,recorder.connection());
                    assertEquals(entry.getValue(),recorder.runs,"独立接口记录器与手写状态基准：" + entry.getKey());
                }
                // 接口专用入口也实际执行，不将不兼容的 H2 存储过程计入数据库验证。
                recorder.runs.clear();
                analyzed.loader().loadClass("org.u80dc.jsqlint.samples.jdbcapi.JdbcApiApplication")
                        .getMethod("exerciseInterfaceOnly",Connection.class).invoke(null,recorder.connection());
                var namedRuns=new ArrayList<>(reference.get("namedCallable")); namedRuns.addAll(reference.get("inOutCallable"));
                assertEquals(namedRuns,recorder.runs);
                compareAnalysis(analyzed.result(),reference);
                ClassLoader previous=Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(analyzed.loader());
                try {
                    Class.forName("org.h2.Driver",true,analyzed.loader());
                    analyzed.loader().loadClass("org.u80dc.jsqlint.samples.jdbcapi.JdbcApiApplication").getMethod("run").invoke(null);
                } finally { Thread.currentThread().setContextClassLoader(previous); }
                metric("h2_database_run",1,1,0,0);
                assertNotEquals(AnalysisResult.Status.FAILED,analyzed.result().status());
                analyzed.result().diagnostics().forEach(value -> evidence.add("DIAGNOSTIC " + value));
            } finally { writeEvidence(); }
        }
        for (String metric:metrics) {
            String[] values=metric.split("\t");
            assertEquals("0",values[3],metric);
            if (Set.of("all_code_sql_sites","standard_sql_signatures","new_setter_signatures","runtime_extraction_sites","positive_context_rows","conservative_alias_contexts",
                    "empty_batch_sites","batch_snapshot_order","h2_database_run").contains(values[0])) assertEquals(values[1],values[2],metric);
            if (values[0].equals("unexpected_unknown_models")) assertEquals("0",values[4],metric);
        }
    }

    private static final Set<String> STATE_METHODS=Set.of("emptyStatementBatch","emptyPreparedBatch","statementConsecutiveBatches",
            "preparedConsecutiveBatches","clearParametersAfterAdd","clearParametersThenBatchClear","clearBatchThenParameters",
            "localAlias","fieldAlias","arrayAlias","wrappedFactory","transactionSavepoint","transactionRollback","generatedKeys","exceptionalBatchRetry");

    private void compareAnalysis(AnalysisResult result,Map<String,List<Run>> reference) {
        var groups=new LinkedHashMap<String,List<SqlExtraction>>();
        result.calls().stream().filter(value -> value.callSite().target().getName().startsWith("execute")
                        || value.callSite().target().getName().equals("nativeSQL"))
                .forEach(value -> groups.computeIfAbsent(owner(value,reference.keySet()),key -> new ArrayList<>()).add(value));
        int sites=0, checked=0, matched=0, unknown=0, rows=0, recovered=0, empty=0, completeEmpty=0, batch=0, ordered=0;
        int proven=0, boundaryContexts=0, boundaryModels=0, unexpectedUnknown=0, checkedTypes=0, matchedTypes=0;
        for (var entry:reference.entrySet()) {
            var extractions=groups.getOrDefault(entry.getKey(),List.of()).stream()
                    .sorted(Comparator.comparingInt(value -> index(value.callSite()))).toList();
            assertEquals(entry.getValue().size(),extractions.size(),"实际执行/提交位置：" + entry.getKey());
            sites+=extractions.size();
            for (int ordinal=0;ordinal<extractions.size();ordinal++) {
                var extraction=extractions.get(ordinal); var expected=entry.getValue().get(ordinal);
                assertEquals(expected.api,extraction.callSite().target().getName());
                if (expected.rows.isEmpty()) {
                    empty++; boolean isEmpty=extraction.complete() && extraction.statements().isEmpty() && extraction.samples().isEmpty();
                    if (isEmpty) completeEmpty++;
                    evidence.add("EMPTY " + entry.getKey() + " match=" + isEmpty + " complete=" + extraction.complete());
                    continue;
                }
                rows+=expected.rows.size(); var seen=new HashSet<Row>();
                var snapshots=new LinkedHashMap<org.u80dc.jsqlint.core.spi.SourceLocation,Set<Row>>();
                for (var sample:extraction.samples()) {
                    var actual=row(sample);
                    if (actual.isEmpty()) {
                        unknown++;
                        if (entry.getKey().equals("arrayAlias")) {
                            boundaryModels++;
                            assertEquals(SqlSample.Feasibility.UNKNOWN,sample.feasibility());
                            assertTrue(sample.sql().isEmpty()); assertFalse(sample.assessment().sourcesComplete());
                            assertTrue(sample.issues().stream().anyMatch(value -> value.contains("LIMIT") || value.contains("receiver")));
                        } else unexpectedUnknown++;
                        evidence.add("UNKNOWN " + entry.getKey() + " sql=" + sample.sql() + " status=" + sample.feasibility()
                                + " bindings=" + sample.bindings() + " assessment=" + sample.assessment() + " issues=" + sample.issues());
                        continue;
                    }
                    checked++; boolean correct=expected.rows.contains(actual.get()); if (correct) matched++;
                    for (var binding:sample.bindings()) if (binding.value() instanceof SqlValue.Known known && known.value()!=null) {
                        checkedTypes++; boolean typeMatches=known.value().getClass().getName().equals(boxed(binding.type()));
                        if (typeMatches) matchedTypes++;
                        else evidence.add("BINDING_TYPE " + entry.getKey() + " actual=" + binding.type() + " expected=" + known.value().getClass().getName());
                    }
                    if (entry.getKey().equals("exceptionalBatchRetry") && ordinal==1)
                        assertEquals(SqlSample.Feasibility.UNKNOWN,sample.feasibility(),"异常后的重试依赖未建模异常边");
                    seen.add(actual.get());
                    if (sample.feasibility()==SqlSample.Feasibility.SATISFIABLE) proven++;
                    sample.batchEntry().ifPresent(location -> snapshots.computeIfAbsent(location,key -> new HashSet<>()).add(actual.get()));
                    evidence.add("MODEL " + entry.getKey() + " ordinal=" + ordinal + " " + sample.feasibility()
                            + " match=" + correct + " actual=" + actual.get() + " expected=" + expected.rows);
                }
                recovered+=(int)expected.rows.stream().filter(seen::contains).count();
                if (entry.getKey().equals("arrayAlias")) {
                    assertTrue(seen.isEmpty(),"默认预算下的数组别名作为明确的保守边界单列");
                    assertFalse(extraction.complete()); assertFalse(extraction.samples().isEmpty()); boundaryContexts++;
                }
                if (expected.api.equals("executeBatch") || expected.api.equals("executeLargeBatch")) {
                    var locations=extraction.statements().stream().flatMap(value -> value.batchEntry().stream()).distinct().toList();
                    assertEquals(expected.rows.size(),locations.size(),"批次加入位置：" + entry.getKey());
                    for (int i=0;i<locations.size();i++) {
                        batch++; boolean correct=Set.of(expected.rows.get(i)).equals(snapshots.get(locations.get(i)));
                        if (correct) ordered++;
                        evidence.add("BATCH " + entry.getKey() + " ordinal=" + ordinal + " index=" + i + " match=" + correct);
                    }
                }
            }
        }
        metric("runtime_extraction_sites",reference.values().stream().mapToInt(List::size).sum(),sites,0,0);
        metric("execution_context_rows",rows,recovered,0,rows-recovered);
        metric("positive_context_rows",rows-1,recovered,0,rows-1-recovered);
        metric("conservative_alias_contexts",1,boundaryContexts,0,1-boundaryContexts);
        metric("unresolved_alias_models",boundaryModels,0,0,boundaryModels);
        metric("unexpected_unknown_models",unexpectedUnknown,0,0,unexpectedUnknown);
        metric("sql_and_bindings",checked,matched,checked-matched,unknown);
        metric("binding_types",checkedTypes,matchedTypes,checkedTypes-matchedTypes,0);
        metric("empty_batch_sites",empty,completeEmpty,empty-completeEmpty,0);
        metric("batch_snapshot_order",batch,ordered,batch-ordered,0);
        metric("proven_models",checked,proven,0,checked-proven);
    }

    private static String owner(SqlExtraction extraction,Set<String> methods) {
        String caller=extraction.callSite().caller().orElseThrow().getName();
        if (methods.contains(caller)) return caller;
        return extraction.callChain().stream().map(site -> site.target().getName()).filter(methods::contains).reduce((first,last) -> last)
                .orElseThrow(() -> new IllegalStateException("Missing independent oracle for " + extraction.callSite()));
    }
    private static int index(SqlCallSite site) {
        String location=site.location().element(); return Integer.parseInt(location.substring(location.lastIndexOf('@')+1).strip());
    }

    private void signatureInventory(Path expanded) throws Exception {
        var actual=memberCalls(expanded);
        for (String name:List.of("jdbc-user-query","jdbc-usage-matrix","springmvc-jdbc-user-query")) {
            var options=new InputLocationOptions(InputMode.AUTO,List.of(),List.of("-o"),null).withClassOutputDirectory(temporary.resolve("inventory-"+name));
            try (var input=new AutoCompileAnalysisInputLocation(root().resolve("shared-test-resources").resolve(name),options)) {
                actual.addAll(memberCalls(input.getClassOutputDirectory()));
            }
        }
        Set<String> sql=new TreeSet<>();
        addCatalog(sql,Connection.class,Set.of("createStatement","prepareStatement","prepareCall","nativeSQL"));
        addCatalog(sql,Statement.class,Set.of("execute","executeQuery","executeUpdate","executeLargeUpdate","addBatch","executeBatch","executeLargeBatch"));
        addCatalog(sql,PreparedStatement.class,Set.of("execute","executeQuery","executeUpdate","executeLargeUpdate","addBatch"));
        assertEquals(34,sql.size(),"JDK21 的有限 SQL 签名目录");
        reportCatalog("standard_sql_signatures",sql,actual);
        Set<String> positional=Arrays.stream(PreparedStatement.class.getDeclaredMethods()).filter(value -> value.getName().startsWith("set"))
                .map(JdbcStandardApiCoverageTest::signature).collect(Collectors.toCollection(TreeSet::new));
        Set<String> named=Arrays.stream(CallableStatement.class.getDeclaredMethods()).filter(value -> value.getName().startsWith("set"))
                .map(JdbcStandardApiCoverageTest::signature).collect(Collectors.toCollection(TreeSet::new));
        reportCatalog("positional_setter_signatures",positional,actual);
        reportCatalog("named_setter_signatures",named,actual);
        Set<String> required=new TreeSet<>();
        for (Object[] method:List.of(new Object[]{"setByte",byte.class},new Object[]{"setShort",short.class},new Object[]{"setLong",long.class},
                new Object[]{"setFloat",float.class},new Object[]{"setDouble",double.class}))
            required.add(signature(PreparedStatement.class.getDeclaredMethod((String)method[0],int.class,(Class<?>)method[1])));
        required.add(signature(PreparedStatement.class.getDeclaredMethod("setObject",int.class,Object.class)));
        required.add(signature(PreparedStatement.class.getDeclaredMethod("setObject",int.class,Object.class,int.class,int.class)));
        required.add(signature(PreparedStatement.class.getDeclaredMethod("setNull",int.class,int.class,String.class)));
        required.add(signature(CallableStatement.class.getDeclaredMethod("setString",String.class,String.class)));
        required.add(signature(CallableStatement.class.getDeclaredMethod("setInt",String.class,int.class)));
        required.add(signature(CallableStatement.class.getDeclaredMethod("setNull",String.class,int.class)));
        required.add(signature(CallableStatement.class.getDeclaredMethod("setObject",String.class,Object.class,SQLType.class)));
        reportCatalog("new_setter_signatures",required,actual);
    }
    private static void addCatalog(Set<String> target,Class<?> type,Set<String> names) {
        Arrays.stream(type.getDeclaredMethods()).filter(value -> names.contains(value.getName())).map(JdbcStandardApiCoverageTest::signature).forEach(target::add);
    }
    private static String signature(Method method) { return method.getName()+org.objectweb.asm.Type.getMethodDescriptor(method); }
    private static Set<String> memberCalls(Path classes) throws Exception {
        Set<String> calls=new TreeSet<>();
        try (var files=Files.walk(classes)) {
            for (var path:files.filter(value -> value.toString().endsWith(".class")).toList())
                new ClassReader(Files.readAllBytes(path)).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int opcode,String owner,String name,String descriptor,boolean isInterface) {
                                if (Set.of("java/sql/Connection","java/sql/Statement","java/sql/PreparedStatement","java/sql/CallableStatement").contains(owner))
                                    calls.add(name+descriptor);
                            }
                        };
                    }
                },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        return calls;
    }
    private void reportCatalog(String name,Set<String> expected,Set<String> actual) {
        var matched=new TreeSet<>(expected); matched.retainAll(actual);
        var missing=new TreeSet<>(expected); missing.removeAll(actual);
        metric(name,expected.size(),matched.size(),0,missing.size()); evidence.add("SIGNATURES " + name + " missing=" + missing);
    }

    private static Map<String,List<Run>> references() {
        var expected=new LinkedHashMap<String,List<Run>>();
        expected.put("createTables",List.of(run("execute",r("CREATE TABLE jdbc_api (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, name VARCHAR(80))")),
                run("execute",r("CREATE TABLE jdbc_state (case_name VARCHAR(80), row_value INTEGER)")),
                run("execute",r("CREATE TABLE jdbc_generated (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, label VARCHAR(80))"))));
        expected.put("createStatementHoldability",List.of(run("executeQuery",r("SELECT 101"))));
        expected.put("scrollingPrepared",List.of(run("executeQuery",r("SELECT ? /* scroll prepared */",1,11))));
        expected.put("plainCallable",List.of(run("execute",r("{? = call ABS(?)} /* plain callable */",2,-12))));
        expected.put("holdableCallable",List.of(run("execute",r("{? = call ABS(?)} /* holdable callable */",2,-13))));
        expected.put("nativeSql",List.of(run("nativeSQL",r("SELECT {fn ABS(-14)}"))));
        for (String[] value:List.of(new String[]{"executeGeneratedFlag","execute flag"},new String[]{"executeGeneratedIndexes","execute indexes"},
                new String[]{"executeGeneratedNames","execute names"},new String[]{"updateGeneratedFlag","update flag"},
                new String[]{"updateGeneratedIndexes","update indexes"},new String[]{"updateGeneratedNames","update names"},
                new String[]{"largeUpdatePlain","large plain"},new String[]{"largeUpdateGeneratedFlag","large flag"},
                new String[]{"largeUpdateGeneratedIndexes","large indexes"},new String[]{"largeUpdateGeneratedNames","large names"})) {
            String api=value[0].startsWith("execute") ? "execute" : value[0].startsWith("update") ? "executeUpdate" : "executeLargeUpdate";
            expected.put(value[0],List.of(run(api,r("INSERT INTO jdbc_api (name) VALUES ('"+value[1]+"')"))));
        }
        expected.put("numericBindings",List.of(run("executeQuery",r("SELECT ?, ?, ?, ?, ?, ?, ?, ? /* numeric bindings */",
                1,(byte)7,2,(short)32000,3,9_000_000_000L,4,1.25f,5,2.5d,6,"plain object",7,42,8,null))));
        expected.put("dynamicIndex",List.of(run("executeQuery",r("SELECT ?, ? /* dynamic index */",1,77,2,88))));
        expected.put("namedCallable",List.of(run("execute",r("{call JDBC_NAMED(?, ?, ?, ?, ?)}","name","Ada","number",42,"optional",null,"object","named object"))));
        expected.put("inOutCallable",List.of(run("execute",r("{call JDBC_INOUT(?, ?, ?)}",1,7))));
        expected.put("countRows",List.of(run("executeQuery",r("SELECT COUNT(*) FROM jdbc_api"))));
        expected.put("emptyStatementBatch",List.of(run("executeBatch")));
        expected.put("emptyPreparedBatch",List.of(run("executeBatch")));
        expected.put("statementConsecutiveBatches",List.of(run("executeBatch",stateLiteral("statement_first",11)),run("executeBatch",stateLiteral("statement_second",12))));
        expected.put("preparedConsecutiveBatches",List.of(run("executeBatch",stateBound("prepared_retained",21)),run("executeBatch",stateBound("prepared_retained",21))));
        expected.put("clearParametersAfterAdd",List.of(run("executeBatch",stateBound("clear_parameters_after_add",31),stateBound("clear_parameters_after_add",32))));
        expected.put("clearParametersThenBatchClear",List.of(run("executeBatch",stateBound("clear_parameters_then_batch",33))));
        expected.put("clearBatchThenParameters",List.of(run("executeBatch",stateBound("clear_batch_then_parameters",905),stateBound("clear_batch_then_parameters",34))));
        expected.put("localAlias",List.of(run("executeUpdate",stateBound("local_alias",41))));
        expected.put("fieldAlias",List.of(run("executeUpdate",stateBound("field_alias",42))));
        expected.put("arrayAlias",List.of(run("executeUpdate",stateBound("array_alias",43))));
        expected.put("wrappedFactory",List.of(run("executeUpdate",stateBound("wrapped_factory",44))));
        expected.put("transactionSavepoint",List.of(run("executeUpdate",stateLiteral("tx_kept",71)),run("executeUpdate",stateLiteral("tx_reverted",72)),run("executeUpdate",stateLiteral("tx_committed",73))));
        expected.put("transactionRollback",List.of(run("executeUpdate",stateLiteral("tx_full_rollback",81))));
        expected.put("generatedKeys",List.of(run("executeUpdate",r("INSERT INTO jdbc_generated (label) VALUES ('generated_key')"))));
        expected.put("exceptionalBatchRetry",List.of(run("executeBatch",stateLiteral("retry_first",91),r("INSERT INTO jdbc_missing_retry (row_value) VALUES (92)")),run("executeBatch",stateLiteral("retry_after_clear",93))));
        expected.put("verifyRows",List.of(run("executeQuery",r("SELECT COUNT(*) FROM jdbc_state")),
                run("executeQuery",r("SELECT COUNT(*) FROM jdbc_state WHERE case_name IN ('tx_reverted', 'tx_full_rollback', 'empty_statement_removed', 'empty_prepared_removed')")),
                run("executeQuery",r("SELECT COUNT(*) FROM jdbc_state WHERE case_name='prepared_retained' AND row_value=21")),
                run("executeQuery",r("SELECT COUNT(*) FROM jdbc_generated WHERE label='generated_key'"))));
        return expected;
    }
    private static Row stateBound(String name,int value) { return r("INSERT INTO jdbc_state VALUES ('"+name+"', ?)",1,value); }
    private static Row stateLiteral(String name,int value) { return r("INSERT INTO jdbc_state VALUES ('"+name+"', "+value+")"); }
    private static Run run(String api,Row... rows) { return new Run(api,List.of(rows)); }
    private record Run(String api,List<Row> rows) {}
    private record Row(String sql,Map<String,Object> bindings) {
        Row { sql=canonical(sql); bindings=Collections.unmodifiableMap(new TreeMap<>(bindings)); }
    }
    private static String key(Object value) {
        if (value instanceof Integer position && position>0) return "position:"+position;
        if (value instanceof String name && !name.isEmpty()) return "name:"+name;
        throw new IllegalStateException("Invalid JDBC key without lossless int/string identity: " + value);
    }
    private static String boxed(String type) {
        return switch (type) {
            case "byte" -> Byte.class.getName(); case "short" -> Short.class.getName(); case "int" -> Integer.class.getName();
            case "long" -> Long.class.getName(); case "float" -> Float.class.getName(); case "double" -> Double.class.getName();
            case "boolean" -> Boolean.class.getName(); default -> type;
        };
    }
    private static Row r(String sql,Object... bindings) {
        var values=new TreeMap<String,Object>(); for (int i=0;i<bindings.length;i+=2) values.put(key(bindings[i]),bindings[i+1]); return new Row(sql,values);
    }
    private static Optional<Row> row(SqlSample sample) {
        if (sample.sql().isEmpty()) return Optional.empty();
        var bindings=new TreeMap<String,Object>();
        for (var binding:sample.bindings()) {
            if (!(binding.keyValue() instanceof SqlValue.Known knownKey) || !(binding.value() instanceof SqlValue.Known value)) return Optional.empty();
            String key=key(knownKey.value()); if (bindings.containsKey(key)) throw new IllegalStateException("Duplicated JDBC binding " + key);
            bindings.put(key,value.value());
        }
        return Optional.of(new Row(sample.sql().get(),bindings));
    }

    private void metric(String name,int total,int matched,int wrong,int unknown) {
        metrics.add(String.join("\t",name,""+total,""+matched,""+wrong,""+unknown));
    }
    private void writeEvidence() throws Exception {
        Path target=root().resolve("jsqlint-test/target"); Files.createDirectories(target);
        Files.writeString(target.resolve("jdbc-standard-api-metrics.tsv"),"metric\texpected_or_checked\tmatched\twrong\tuncheckable\n"+String.join("\n",metrics)+"\n");
        Files.writeString(target.resolve("jdbc-standard-api-evidence.txt"),String.join("\n",evidence)+"\n");
    }

    /** 同时记录位置和命名绑定；批次快照不可被后续 setters 改写。故障提交也保留原始 SQL。 */
    private static final class Recorder {
        final List<Run> runs=new ArrayList<>();
        Connection connection() {
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args) -> switch (method.getName()) {
                case "createStatement" -> statement(null,false);
                case "prepareStatement" -> statement((String)args[0],false);
                case "prepareCall" -> statement((String)args[0],true);
                case "nativeSQL" -> { runs.add(run("nativeSQL",r((String)args[0]))); yield args[0]; }
                default -> fallback(method.getReturnType());
            });
        }
        Object statement(String sql,boolean callable) {
            var values=new TreeMap<String,Object>(); var queued=new ArrayList<Row>();
            Class<?> api=callable ? CallableStatement.class : sql==null ? Statement.class : PreparedStatement.class;
            return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{api},(proxy,method,args) -> {
                if (method.getName().startsWith("set") && args!=null && args.length>=2 && (args[0] instanceof Integer || args[0] instanceof String)) {
                    values.put(key(args[0]),method.getName().equals("setNull") ? null : args[1]); return null;
                }
                if (method.getName().equals("clearParameters")) { values.clear(); return null; }
                if (method.getName().equals("addBatch")) { queued.add(new Row(sql==null ? (String)args[0] : sql,values)); return null; }
                if (method.getName().equals("clearBatch")) { queued.clear(); return null; }
                if (method.getName().equals("executeBatch") || method.getName().equals("executeLargeBatch")) {
                    var submitted=List.copyOf(queued); runs.add(new Run(method.getName(),submitted));
                    if (submitted.stream().anyMatch(value -> value.sql.contains("jdbc_missing_retry")))
                        throw new BatchUpdateException("Injected missing table",new int[]{1,Statement.EXECUTE_FAILED});
                    queued.clear();
                    if (method.getReturnType()==int[].class) { var counts=new int[submitted.size()]; Arrays.fill(counts,1); return counts; }
                    var counts=new long[submitted.size()]; Arrays.fill(counts,1L); return counts;
                }
                if (method.getName().startsWith("execute")) {
                    runs.add(run(method.getName(),new Row(sql==null ? (String)args[0] : sql,values)));
                    if (method.getReturnType()==ResultSet.class) return resultSet();
                    if (method.getReturnType()==int.class) return 1; if (method.getReturnType()==long.class) return 1L;
                    if (method.getReturnType()==boolean.class) return false;
                }
                if (method.getName().equals("getGeneratedKeys")) return resultSet();
                return fallback(method.getReturnType());
            });
        }
        Object resultSet() {
            boolean[] first={true};
            return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(proxy,method,args) -> {
                if (method.getName().equals("next")) { boolean current=first[0]; first[0]=false; return current; }
                if (method.getName().equals("getLong")) return 1L;
                return fallback(method.getReturnType());
            });
        }
        private static Object fallback(Class<?> type) {
            if (!type.isPrimitive() || type==void.class) return null;
            if (type==boolean.class) return false; if (type==byte.class) return (byte)0; if (type==short.class) return (short)0;
            if (type==int.class) return 0; if (type==long.class) return 0L; if (type==float.class) return 0f; if (type==double.class) return 0d;
            if (type==char.class) return '\0'; throw new IllegalArgumentException(type.toString());
        }
    }
}
