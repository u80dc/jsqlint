package org.u80dc.jsqlint.validation;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.u80dc.jsqlint.commons.location.*;
import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.resource.PathResourceSource;
import org.u80dc.jsqlint.core.spi.SqlCallSite;
import org.u80dc.jsqlint.mybatis.MybatisAnalysisModule;
import org.u80dc.jsqlint.mybatis.MybatisOptions;
import sootup.java.bytecode.frontend.inputlocation.DefaultRuntimeAnalysisInputLocation;

import javax.sql.DataSource;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 用原样例执行到 JDBC 边界及真实 MyBatis BoundSql 独立核验，报告漏提取、错提取与不可核验模型。 */
@Isolated("Maven Embedder 和 MyBatis Resources 使用进程共享状态")
class SampleSqlCoverageTest {
    private static final String SELECT = "SELECT id, username, email, age, active FROM app_users";
    private static final String MAPPER = "org.u80dc.jsqlint.samples.mybatis.UserMapper";
    @TempDir Path temporary;
    private final List<String> metrics = new ArrayList<>();
    private final List<String> evidence = new ArrayList<>();

    @Test @Tag("integration")
    void comparesCurrentJdbcSamplesWithIndependentOracles() throws Exception {
        try {
            jdbc();
            springJdbc();
            jdbcMatrix();
        } finally { writeEvidence("jdbc-current-sample"); }
        assertMetrics();
    }

    @Test @Tag("integration")
    void comparesExpandedUsageMatricesWithRuntimeAndDatabaseOracles() throws Exception {
        try {
            jdbcMatrix();
            mybatisMatrix("h2");
            mybatisMatrix(null);
        } finally { writeEvidence("sample-usage-matrix"); }
        assertMetrics();
    }

    @Test @Tag("integration")
    void comparesAllSharedSamplesWithIndependentRuntimeSqlOracles() throws Exception {
        jdbc();
        springJdbc();
        mybatis();
        writeEvidence("sample-sql-coverage");
        assertMetrics();
    }

    private void writeEvidence(String name) throws Exception {
        Path target = root().resolve("jsqlint-test/target");
        Files.createDirectories(target);
        Files.writeString(target.resolve(name + "-metrics.tsv"),
                "project\tmetric\texpected_or_checked\tmatched\textra_or_wrong\tuncheckable\n" + String.join("\n", metrics) + "\n");
        Files.writeString(target.resolve(name + "-evidence.txt"), String.join("\n", evidence) + "\n");
    }

    private void assertMetrics() {
        // 先写证据再执行契约断言，回归失败时仍能定位漏提取、错误值及运行时边界。
        for (String metric:metrics) {
            String[] fields=metric.split("\t");
            assertEquals("0",fields[4],metric);
            if (Set.of("sql_sites","definitions","sql_source_methods","raw_structures","main_invocations",
                    "runtime_rows","ordered_batch_rows","database_runs","sql_api_pairs","code_sql_sites").contains(fields[1]))
                assertEquals(fields[2],fields[3],metric);
            if ((fields[0].startsWith("jdbc-usage-matrix") || fields[0].startsWith("mybatis-usage-matrix"))
                    && Set.of("sql_and_bindings","explicit_definition_cases").contains(fields[1]))
                assertEquals("0",fields[5],metric);
            if (Set.of("jdbc-user-query","springmvc-jdbc-user-query").contains(fields[0])
                    && Set.of("sql_and_bindings","execution_model_sql_and_bindings").contains(fields[1]))
                assertEquals("0",fields[5],metric);
        }
    }

    private void jdbcMatrix() throws Exception {
        String project = "jdbc-usage-matrix";
        try (var analyzed = analyze(project)) {
            Class<?> scenarios = analyzed.loader.loadClass("org.u80dc.jsqlint.samples.jdbcmatrix.JdbcScenarios");
            var recorder = new JdbcRecorder();
            var reference = new LinkedHashMap<String,List<SqlRow>>();
            reference.put("createTable", List.of(new SqlRow("CREATE TABLE jdbc_usage (id INTEGER PRIMARY KEY, name VARCHAR(80), score INTEGER)", Map.of())));
            var nullable = new TreeMap<Integer,Object>(); nullable.put(1,1); nullable.put(2,"O'Neil"); nullable.put(3,null);
            reference.put("overwriteAndClear", List.of(new SqlRow("INSERT INTO jdbc_usage VALUES (?, ?, ?)",nullable)));
            reference.put("preparedBatch", List.of(
                    new SqlRow("INSERT INTO jdbc_usage (id, name) VALUES (?, ?)",Map.of(1,11,2,"first")),
                    new SqlRow("INSERT INTO jdbc_usage (id, name) VALUES (?, ?)",Map.of(1,12,2,"second"))));
            reference.put("statementBatch", List.of(
                    new SqlRow("INSERT INTO jdbc_usage (id, name) VALUES (21, 'plain first')",Map.of()),
                    new SqlRow("INSERT INTO jdbc_usage (id, name) VALUES (22, 'plain second')",Map.of())));
            reference.put("preparedLargeBatch", List.of(
                    new SqlRow("INSERT INTO jdbc_usage (id, score) VALUES (?, ?)",Map.of(1,31,2,310)),
                    new SqlRow("INSERT INTO jdbc_usage (id, score) VALUES (?, ?)",Map.of(1,32,2,320))));
            reference.put("statementLargeBatch", List.of(
                    new SqlRow("INSERT INTO jdbc_usage (id, name) VALUES (41, 'large first')",Map.of()),
                    new SqlRow("INSERT INTO jdbc_usage (id, name) VALUES (42, 'large second')",Map.of())));
            reference.put("largeUpdate", List.of(new SqlRow("UPDATE jdbc_usage SET score=? WHERE id=?",Map.of(1,110,2,11))));
            reference.put("callable", List.of(new SqlRow("{? = call ABS(?)}",Map.of(2,-7))));
            reference.put("scrollingQuery", List.of(new SqlRow("SELECT id, name, score FROM jdbc_usage ORDER BY id",Map.of())));
            for (var entry : reference.entrySet()) {
                recorder.rows.clear();
                scenarios.getMethod(entry.getKey(),Connection.class).invoke(null,recorder.connection());
                assertEquals(entry.getValue(),recorder.rows,"独立 JDBC 记录器应符合手写的执行状态基准：" + entry.getKey());
            }
            var expectedSites = new HashSet<String>();
            reference.keySet().forEach(name -> expectedSites.add(name + "/" + switch (name) {
                case "createTable","callable" -> "execute";
                case "overwriteAndClear" -> "executeUpdate";
                case "preparedBatch","statementBatch" -> "executeBatch";
                case "preparedLargeBatch","statementLargeBatch" -> "executeLargeBatch";
                case "largeUpdate" -> "executeLargeUpdate";
                default -> "executeQuery";
            }));
            for (String name : List.of("overwriteAndClear","preparedBatch","preparedLargeBatch","largeUpdate","scrollingQuery"))
                expectedSites.add(name + "/prepareStatement");
            expectedSites.add("callable/prepareCall");
            expectedSites.add("statementBatch/addBatch"); expectedSites.add("statementLargeBatch/addBatch");
            expectedSites.add("preparedBatch/addBatch"); expectedSites.add("preparedLargeBatch/addBatch");
            compareSet(project,"sql_api_pairs",expectedSites,jdbcSites(analyzed.result));
            compareCodeSites(project,analyzed);
            compareSet(project,"sql_source_methods",reference.keySet(),analyzed.result.calls().stream()
                    .map(value -> value.callSite().caller().orElseThrow().getName()).collect(Collectors.toSet()));
            int checked=0, correct=0, unavailable=0, recovered=0, ordered=0;
            for (var extraction : executions(analyzed.result)) {
                String name=extraction.callSite().caller().orElseThrow().getName();
                var expected=reference.get(name); var seen=new HashSet<SqlRow>();
                var queued=new LinkedHashMap<org.u80dc.jsqlint.core.spi.SourceLocation,Set<SqlRow>>();
                for (var sample : extraction.samples()) {
                    var row=savedRow(sample);
                    if (row.isEmpty()) { unavailable++; evidence.add("JDBC_MATRIX_UNCHECKABLE " + name + " " + sample); continue; }
                    checked++; boolean matches=expected.contains(row.get()); if (matches) correct++;
                    seen.add(row.get());
                    sample.batchEntry().ifPresent(location -> queued.computeIfAbsent(location,key -> new HashSet<>()).add(row.get()));
                    evidence.add("JDBC_MATRIX " + name + " " + sample.feasibility() + " batch=" + sample.batchEntry()
                            + " match=" + matches + " actual=" + row.get());
                }
                recovered += (int) expected.stream().filter(seen::contains).count();
                if (name.endsWith("Batch")) {
                    // 每个加入位置的模型只允许自己的快照；另外检查加入时的源码顺序。
                    var locations=extraction.statements().stream().flatMap(value -> value.batchEntry().stream()).distinct().toList();
                    assertEquals(expected.size(),locations.size(),name);
                    for (int i=0;i<locations.size();i++) {
                        boolean matches=Set.of(expected.get(i)).equals(queued.get(locations.get(i)));
                        if (matches) ordered++;
                        evidence.add("BATCH_ORDER " + name + " index=" + i + " match=" + matches + " actual=" + queued.get(locations.get(i)));
                    }
                }
            }
            metric(project,"runtime_rows",13,recovered,0,0);
            metric(project,"ordered_batch_rows",8,ordered,8-ordered,0);
            metric(project,"sql_and_bindings",checked,correct,checked-correct,unavailable);
            // 真正运行 H2 样例的全部自检：记录器不能证明数据库接受 SQL 或正确处理批次。
            withLoader(analyzed.loader, () -> {
                Class.forName("org.h2.Driver",true,analyzed.loader);
                analyzed.loader.loadClass("org.u80dc.jsqlint.samples.jdbcmatrix.JdbcUsageApplication").getMethod("run").invoke(null);
            });
            metric(project,"database_runs",1,1,0,0);
            assess(project,analyzed.result);
        }
    }

    private void mybatisMatrix(String databaseId) throws Exception {
        String name="mybatis-usage-matrix", project=name + ":" + (databaseId==null ? "default" : databaseId);
        String mapperName="org.u80dc.jsqlint.samples.mybatismatrix.UsageMapper";
        var options=MybatisOptions.defaults().withDatabaseId(databaseId).withVariables(Map.of("sampleTable","mybatis_usage"));
        try (var analyzed=analyze(name,options)) {
            var configuration=new Configuration(); configuration.setDatabaseId(databaseId);
            var variables=new Properties(); variables.setProperty("sampleTable","mybatis_usage"); configuration.setVariables(variables);
            withLoader(analyzed.loader, () -> {
                for (String resource:List.of("mappers/UsageMapper.xml","mappers/SharedColumns.xml"))
                    try (var stream=Files.newInputStream(root().resolve("shared-test-resources").resolve(name).resolve("src/main/resources").resolve(resource))) {
                        new XMLMapperBuilder(stream,configuration,resource,configuration.getSqlFragments()).parse();
                    }
            });
            Class<?> mapper=analyzed.loader.loadClass(mapperName);
            var invocations=List.of(call("createTable"),call("insertXml",1L,"O'Neil",null),call("insertXml",2L,"Bob","bob@example.test"),
                    call("chooseSearch",1L,"ignored"),call("chooseSearch",0L,"Bob"),call("chooseSearch",0L,null),
                    call("trimUpdate",1L,"Alice","alice@example.test"),call("trimUpdate",2L,"Bobby",null),
                    call("implicitNames",1L,"Bobby"),call("scalarFallback",1L),call("repeated",2L),call("included",1L),
                    call("databaseVariant",1L),call("deleteXml",2L));
            Set<String> methods=invocations.stream().map(Invocation::method).collect(Collectors.toSet());
            compareSet(project,"definitions",methods,analyzed.result.definitions().stream()
                    .map(value -> value.definition().orElseThrow().id().substring(mapperName.length()+1)).collect(Collectors.toSet()));
            compareSet(project,"sql_sites",methods,analyzed.result.calls().stream().map(value -> value.callSite().target().getName()).collect(Collectors.toSet()));
            var oracle=new HashMap<String,SqlRow>(); var ranks=new HashMap<String,Integer>();
            for (var invocation:invocations)
                oracle.put(invocation.method + "#" + ranks.merge(invocation.method,1,Integer::sum),mybatisRow(configuration,mapper,invocation));
            var sites=analyzed.result.calls().stream().map(SqlExtraction::callSite).distinct()
                    .sorted(Comparator.comparingInt(SampleSqlCoverageTest::statementIndex)).toList();
            var keys=new HashMap<SqlCallSite,String>(); ranks.clear();
            for (var site:sites) keys.put(site,site.target().getName() + "#" + ranks.merge(site.target().getName(),1,Integer::sum));
            assertEquals(invocations.size(),keys.size(),"exercise 的每次调用应独立保留");
            metric(project,"code_sql_sites",invocations.size(),keys.size(),0,0);
            int checked=0, correct=0, unavailable=0; var recovered=new HashSet<String>();
            for (var extraction:analyzed.result.calls()) for (var sample:extraction.samples()) {
                String key=keys.get(extraction.callSite()); var actual=savedRow(sample);
                if (actual.isEmpty()) { unavailable++; evidence.add("MYBATIS_MATRIX_UNKNOWN " + key + " " + sample); continue; }
                checked++; boolean matches=actual.get().equals(oracle.get(key));
                if (matches) { correct++; recovered.add(key); }
                evidence.add("MYBATIS_MATRIX " + project + " " + key + " " + sample.feasibility() + " match=" + matches
                        + " actual=" + actual.get() + " expected=" + oracle.get(key));
            }
            metric(project,"main_invocations",invocations.size(),recovered.size(),0,0);
            metric(project,"sql_and_bindings",checked,correct,checked-correct,unavailable);
            var cases=new ArrayList<>(invocations);
            cases.add(call("chooseSearch",0L,"")); cases.add(call("chooseSearch",7L,null));
            cases.add(call("trimUpdate",1L,null,"email only"));
            cases.add(call("implicitNames",0L,"O'Neil")); cases.add(call("scalarFallback",0L));
            var definitions=analyzed.result.definitions().stream().collect(Collectors.toMap(
                    value -> value.definition().orElseThrow().id().substring(mapperName.length()+1),Function.identity()));
            checked=0; correct=0; unavailable=0;
            for (var invocation:cases) {
                Object named=namedParams(configuration,mapper,invocation);
                Map<String,?> parameters=named==null ? Map.of() : named instanceof Map<?,?> ? (Map<String,?>)named : Map.of("anything",named);
                var sample=definitions.get(invocation.method).sample(parameters); var actual=savedRow(sample);
                if (actual.isEmpty()) { unavailable++; evidence.add("MYBATIS_MATRIX_DEFINITION_UNKNOWN " + invocation + " " + sample); continue; }
                checked++; var expected=mybatisRow(configuration,mapper,invocation); boolean matches=actual.get().equals(expected); if (matches) correct++;
                evidence.add("MYBATIS_MATRIX_DEFINITION " + project + " " + invocation + " match=" + matches
                        + " actual=" + actual.get() + " expected=" + expected);
            }
            metric(project,"explicit_definition_cases",checked,correct,checked-correct,unavailable);
            // 运行与分析相同的配置，SQL/绑定之外还核验结果、XML DML 和动态条件实际可执行。
            withLoader(analyzed.loader, () -> analyzed.loader.loadClass("org.u80dc.jsqlint.samples.mybatismatrix.MybatisUsageApplication")
                    .getMethod("run",String.class).invoke(null,databaseId));
            metric(project,"database_runs",1,1,0,0);
            assess(project,analyzed.result);
        }
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
    private static void withLoader(ClassLoader loader,CheckedAction action) throws Exception {
        ClassLoader previous=Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try { action.run(); } finally { Thread.currentThread().setContextClassLoader(previous); }
    }

    private void jdbc() throws Exception {
        try (var analyzed = analyze("jdbc-user-query")) {
            var result = analyzed.result;
            compareCodeSites("jdbc-user-query",analyzed);
            var recorder = new JdbcRecorder();
            Class<?> type = analyzed.loader.loadClass("org.u80dc.jsqlint.samples.jdbc.UserRepository");
            Object repository = type.getConstructor(Connection.class).newInstance(recorder.connection());
            // 与 main 的调用序列及实参逐项一致，结果集用空游标替代，仅核验生成 SQL。
            List<Invocation> invocations = List.of(
                    call("createTable"), call("insert", 1L,"alice","alice@example.test",24,true),
                    call("insert",2L,"bob","bob@example.test",31,false), call("insert",3L,"O'Neil","oneil@example.test",37,true),
                    call("findById",1L), call("findUsers","ali",20,true), call("findUsers",null,null,false),
                    call("findUsers","O'Neil",null,false), call("update",2L,"bob.updated@example.test",33,true),
                    call("findById",2L), call("deleteById",1L), call("findById",1L), call("findUsers",null,null,false));
            var oracle = new LinkedHashMap<String, SqlRow>();
            var ranks = new HashMap<String,Integer>();
            for (var invocation : invocations) {
                recorder.rows.clear(); invoke(repository, invocation);
                assertEquals(1, recorder.rows.size());
                oracle.put(invocation.method + "#" + ranks.merge(invocation.method,1,Integer::sum), recorder.rows.getFirst());
            }
            Set<String> expectedSites = Set.of("createTable/execute","insert/executeUpdate","findById/executeQuery",
                    "findUsers/prepareStatement","findUsers/executeQuery","update/prepareStatement","update/executeUpdate","deleteById/executeUpdate");
            compareSet("jdbc-user-query", "sql_sites", expectedSites, jdbcSites(result));
            compareSet("jdbc-user-query","sql_source_methods",Set.of("createTable","insert","findById","findUsers","update","deleteById"),
                    result.calls().stream().map(value -> value.callSite().caller().orElseThrow().getName()).collect(Collectors.toSet()));
            var shapes = new LinkedHashSet<String>();
            shapes.add("CREATE TABLE app_users (id BIGINT PRIMARY KEY, username VARCHAR(100) NOT NULL, email VARCHAR(200) NOT NULL, age INTEGER NOT NULL, active BOOLEAN NOT NULL)");
            shapes.add("INSERT INTO app_users (id, username, email, age, active) VALUES (${id}, '${username}', '${email}', ${age}, ${active})");
            shapes.add(SELECT + " WHERE id = ${id}");
            shapes.add("UPDATE app_users SET email = '${email}', age = ${age}, active = ${active} WHERE id = ${id}");
            shapes.add("DELETE FROM app_users WHERE id = ${id}");
            for (boolean keyword : List.of(false,true)) for (boolean age : List.of(false,true)) for (boolean active : List.of(false,true)) {
                // 对每种结构实际执行原始构造代码，确认模板的分支基准，而不是只读分析器输出。
                recorder.rows.clear(); invoke(repository, call("findUsers", keyword ? "ali" : null, age ? 20 : null, active));
                String shape = recorder.rows.getFirst().sql.replace("'%ali%'", "'%${keyword}%'").replace("age >= 20", "age >= ${minimumAge}");
                shapes.add(shape);
            }
            assertEquals(13, shapes.size());
            compareSet("jdbc-user-query", "raw_structures", shapes, rawTexts(result));
            var mainSites = result.calls().stream().flatMap(extraction -> extraction.callChain().stream())
                    .filter(site -> site.caller().map(caller -> caller.getName().equals("main")).orElse(false)
                            && site.target().getDeclClassType().toString().endsWith(".UserRepository"))
                    .distinct().sorted(Comparator.comparingInt(SampleSqlCoverageTest::statementIndex)).toList();
            var keys = new HashMap<SqlCallSite,String>(); ranks.clear();
            for (var site : mainSites) keys.put(site, site.target().getName() + "#" + ranks.merge(site.target().getName(),1,Integer::sum));
            assertEquals(13, keys.size(), "main 的 13 次业务调用应有独立上下文");
            var checked = new LinkedHashMap<String,Boolean>(); var matchedOracle = new HashSet<String>();
            var provenInvocations = new HashSet<String>();
            int unavailable = 0, modelChecked = 0, modelCorrect = 0;
            for (var extraction : executions(result)) for (var sample : extraction.samples()) {
                var main = extraction.callChain().stream().filter(keys::containsKey).findFirst().orElseThrow();
                String key = keys.get(main);
                Optional<SqlRow> row = savedRow(sample);
                if (row.isEmpty()) { unavailable++; evidence.add("JDBC_MAIN_UNCHECKABLE " + key + " " + sample); continue; }
                String record = key + " " + row.get();
                boolean matches = row.get().equals(oracle.get(key));
                modelChecked++;
                if (matches) modelCorrect++;
                checked.put(record, matches);
                if (matches) matchedOracle.add(key);
                if (matches && sample.feasibility()==SqlSample.Feasibility.SATISFIABLE) provenInvocations.add(key);
                evidence.add("JDBC_MAIN " + key + " " + sample.feasibility() + " match=" + matches + " actual=" + row.get() + " expected=" + oracle.get(key));
            }
            metric("jdbc-user-query","main_invocations",oracle.size(),matchedOracle.size(),0,0);
            metric("jdbc-user-query","proven_main_invocations",oracle.size(),provenInvocations.size(),0,oracle.size()-provenInvocations.size());
            metric("jdbc-user-query","sql_and_bindings",checked.size(),(int)checked.values().stream().filter(Boolean::booleanValue).count(),
                    (int)checked.values().stream().filter(value -> !value).count(),unavailable);
            metric("jdbc-user-query","execution_model_sql_and_bindings",modelChecked,modelCorrect,modelChecked-modelCorrect,unavailable);
            withLoader(analyzed.loader, () -> {
                Class.forName("org.h2.Driver",true,analyzed.loader);
                analyzed.loader.loadClass("org.u80dc.jsqlint.samples.jdbc.JdbcUserApplication")
                        .getMethod("main",String[].class).invoke(null,(Object)new String[0]);
            });
            metric("jdbc-user-query","database_runs",1,1,0,0);
            assess("jdbc-user-query",result);
        }
    }

    private void springJdbc() throws Exception {
        try (var analyzed = analyze("springmvc-jdbc-user-query")) {
            var result = analyzed.result;
            compareCodeSites("springmvc-jdbc-user-query",analyzed);
            compareSet("springmvc-jdbc-user-query","sql_sites",Set.of("findById/executeQuery","findUsers/prepareStatement","findUsers/executeQuery"),jdbcSites(result));
            compareSet("springmvc-jdbc-user-query","sql_source_methods",Set.of("findById","findUsers"),
                    result.calls().stream().map(value -> value.callSite().caller().orElseThrow().getName()).collect(Collectors.toSet()));
            var shapes = new LinkedHashSet<String>(); shapes.add(SELECT + " WHERE id = ${id}");
            for (String active : List.of(""," AND active = ?")) for (String order : List.of("id ASC","age ASC, id ASC"))
                shapes.add(SELECT + " WHERE username LIKE ? ESCAPE '!' AND age >= ?" + active + " ORDER BY " + order + " LIMIT ${boundedLimit}");
            compareSet("springmvc-jdbc-user-query","raw_structures",shapes,rawTexts(result));
            var recorder = new JdbcRecorder();
            var dataSource = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DataSource.class},
                    (proxy,method,args) -> method.getName().equals("getConnection") ? recorder.connection() : fallback(method.getReturnType()));
            String base = "org.u80dc.jsqlint.samples.springmvcjdbc.";
            Class<?> repositoryType = analyzed.loader.loadClass(base + "repository.JdbcUserRepository");
            Object repository = repositoryType.getConstructor(DataSource.class).newInstance(dataSource);
            Class<?> serviceType = analyzed.loader.loadClass(base + "service.UserService");
            Object service = serviceType.getConstructor(repositoryType).newInstance(repository);
            Class<?> controllerType = analyzed.loader.loadClass(base + "controller.UserController");
            Object controller = controllerType.getConstructor(serviceType).newInstance(service);
            int checked = 0, correct = 0, unavailable = 0;
            for (var extraction : executions(result)) for (var sample : extraction.samples()) {
                var actual = savedRow(sample);
                var entry = extraction.callChain().stream().map(SqlCallSite::caller).flatMap(Optional::stream)
                        .filter(method -> method.getDeclClassType().toString().equals(controllerType.getName())).findFirst().orElseThrow();
                var method = Arrays.stream(controllerType.getMethods()).filter(candidate -> candidate.getName().equals(entry.getName())).findFirst().orElseThrow();
                var arguments = rootArguments(sample,entry,method.getParameterTypes());
                if (actual.isEmpty() || arguments.isEmpty()) {
                    unavailable++; evidence.add("SPRING_UNCHECKABLE " + entry + " " + sample.feasibility() + " inputs=" + sample.combination().map(value -> value.inputs().toString())); continue;
                }
                recorder.rows.clear();
                try { method.invoke(controller,arguments.get()); }
                catch (InvocationTargetException error) {
                    // 空 ResultSet 使 getById 抛业务 404；查询之前的参数校验失败则记录为不匹配。
                    evidence.add("SPRING_RETURN " + entry.getName() + " " + error.getCause().getClass().getName());
                }
                checked++;
                boolean matches = recorder.rows.size() == 1 && actual.get().equals(recorder.rows.getFirst());
                if (matches) correct++;
                evidence.add("SPRING_MODEL " + entry.getName() + Arrays.toString(arguments.get()) + " " + sample.feasibility()
                        + " match=" + matches + " actual=" + actual.get() + " runtime=" + recorder.rows);
            }
            metric("springmvc-jdbc-user-query","sql_and_bindings",checked,correct,checked-correct,unavailable);
            // JDBC 模块只识别代码入口；Spring 自动执行的两个初始化脚本单独计量。
            metric("springmvc-jdbc-user-query","initialization_resource_statements",2,
                    (int)result.sqlResults().stream().filter(value -> value.location().resource().getScheme().equals("file")
                            && value.location().resource().getPath().endsWith(".sql")).count(),0,0);
            assess("springmvc-jdbc-user-query",result);
        }
    }

    private void mybatis() throws Exception {
        try (var analyzed = analyze("mybatis-user-query")) {
            var result = analyzed.result;
            Class<?> mapper = analyzed.loader.loadClass(MAPPER);
            var configuration = new Configuration();
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(analyzed.loader);
            try (var stream = Files.newInputStream(root().resolve("shared-test-resources/mybatis-user-query/src/main/resources/mappers/UserMapper.xml"))) {
                new XMLMapperBuilder(stream,configuration,"mappers/UserMapper.xml",configuration.getSqlFragments()).parse();
            } finally { Thread.currentThread().setContextClassLoader(previous); }
            var invocations = List.of(call("createTable"),call("insert",7L,"O'Neil"),call("find",7L),call("rename",7L,"Alice"),
                    call("search","username","Alice"),call("optional","Ali"),call("delete",7L),call("providerFind","app_users",8L),
                    call("providerInsert",8L,"Bob"),call("providerRename",8L,"Bobby"),call("providerDelete",8L),call("unusedCount"),call("unusedProviderCount"));
            var expected = invocations.stream().map(Invocation::method).collect(Collectors.toSet());
            compareSet("mybatis-user-query","definitions",expected,result.definitions().stream().map(value -> value.definition().orElseThrow().id().substring(MAPPER.length()+1)).collect(Collectors.toSet()));
            Set<String> called = new HashSet<>(expected); called.removeAll(Set.of("unusedCount","unusedProviderCount"));
            compareSet("mybatis-user-query","sql_sites",called,result.calls().stream().map(value -> value.callSite().target().getName()).collect(Collectors.toSet()));
            var oracle = new LinkedHashMap<String,SqlRow>();
            for (var invocation : invocations) oracle.put(invocation.method,mybatisRow(configuration,mapper,invocation));
            int checked = 0, correct = 0, unavailable = 0; var recovered = new HashSet<String>();
            for (var extraction : result.calls()) for (var sample : extraction.samples()) {
                String name = extraction.callSite().target().getName(); var row = savedRow(sample);
                if (row.isEmpty()) { unavailable++; continue; }
                checked++; boolean matches = row.get().equals(oracle.get(name));
                if (matches) { correct++; recovered.add(name); }
                evidence.add("MYBATIS_CALL " + name + " " + sample.feasibility() + " match=" + matches + " actual=" + row.get() + " expected=" + oracle.get(name));
            }
            metric("mybatis-user-query","main_invocations",called.size(),recovered.size(),0,0);
            metric("mybatis-user-query","sql_and_bindings",checked,correct,checked-correct,unavailable);
            var cases = new ArrayList<>(invocations);
            cases.add(call("optional",(Object)null)); cases.add(call("optional",""));
            cases.add(call("providerFind","archive_users",99L)); cases.add(call("search","id","7"));
            var definitions = result.definitions().stream().collect(Collectors.toMap(value -> value.definition().orElseThrow().id().substring(MAPPER.length()+1),Function.identity()));
            checked = 0; correct = 0; unavailable = 0;
            int runtimeErrors=0, rejectedErrors=0;
            for (var invocation : cases) {
                Object named = namedParams(configuration,mapper,invocation);
                Map<String,?> parameters = named == null ? Map.of() : (Map<String,?>)named;
                var sample = definitions.get(invocation.method).sample(parameters);
                var row = savedRow(sample);
                SqlRow reference;
                try { reference = mybatisRow(configuration,mapper,invocation); }
                catch (RuntimeException error) {
                    runtimeErrors++;
                    boolean rejected=sample.sql().isEmpty();
                    if (rejected) rejectedErrors++;
                    evidence.add("MYBATIS_RUNTIME_ERROR " + invocation + " error=" + error + " rejected=" + rejected
                            + " analysis=" + row + " feasibility=" + sample.feasibility());
                    continue;
                }
                if (row.isEmpty()) { unavailable++; continue; }
                checked++; boolean matches = row.get().equals(reference); if (matches) correct++;
                evidence.add("MYBATIS_DEFINITION " + invocation + " match=" + matches + " actual=" + row.get() + " runtime=" + reference);
            }
            metric("mybatis-user-query","explicit_definition_cases",checked,correct,checked-correct,unavailable);
            metric("mybatis-user-query","runtime_error_cases",runtimeErrors,rejectedErrors,runtimeErrors-rejectedErrors,0);
            assess("mybatis-user-query",result);
        }
    }

    private Analyzed analyze(String name) throws Exception {
        return analyze(name,null);
    }

    private Analyzed analyze(String name,MybatisOptions mybatisOptions) throws Exception {
        return analyzeProject(temporary,name,mybatisOptions);
    }

    static Analyzed analyzeProject(Path temporary,String name,MybatisOptions mybatisOptions) throws Exception {
        Path project = root().resolve("shared-test-resources").resolve(name);
        var options = new InputLocationOptions(InputMode.AUTO,List.of(),List.of("-o"),null)
                .withClassOutputDirectory(temporary.resolve(name));
        var input = new AutoCompileAnalysisInputLocation(project,options);
        Path resources = project.resolve("src/main/resources");
        var config=AnalyzerConfig.defaults().withInferenceOptions(InferenceOptions.defaults().withRandomSeed(20261111L));
        var analyzer=mybatisOptions==null ? new Analyzer(config) : new Analyzer(config,List.of(new MybatisAnalysisModule(mybatisOptions)),List.of());
        var result = analyzer.analyze(new AnalysisRequest(List.of(input,new DefaultRuntimeAnalysisInputLocation()),
                Files.isDirectory(resources) ? List.of(new PathResourceSource(resources)) : List.of()));
        assertNotEquals(AnalysisResult.Status.FAILED,result.status(),result.diagnostics().toString());
        var paths = new ArrayList<Path>(); paths.add(input.getClassOutputDirectory()); paths.add(project.resolve("src/main/resources")); paths.addAll(input.getDependencies());
        var urls = paths.stream().map(path -> { try { return path.toUri().toURL(); } catch (Exception error) { throw new IllegalStateException(error); } }).toArray(java.net.URL[]::new);
        return new Analyzed(result,input,new URLClassLoader(urls,SampleSqlCoverageTest.class.getClassLoader()));
    }

    record Analyzed(AnalysisResult result,AutoCompileAnalysisInputLocation input,URLClassLoader loader) implements AutoCloseable {
        public void close() throws Exception { loader.close(); input.close(); }
    }
    private record Invocation(String method,Object[] arguments) { public String toString() { return method + Arrays.toString(arguments); } }
    private static Invocation call(String method,Object... arguments) { return new Invocation(method,arguments); }
    private static void invoke(Object target,Invocation invocation) throws Exception {
        Arrays.stream(target.getClass().getMethods()).filter(method -> method.getName().equals(invocation.method)
                && method.getParameterCount() == invocation.arguments.length).findFirst().orElseThrow().invoke(target,invocation.arguments);
    }
    private static Object namedParams(Configuration configuration,Class<?> mapper,Invocation invocation) {
        var method = Arrays.stream(mapper.getMethods()).filter(value -> value.getName().equals(invocation.method)).findFirst().orElseThrow();
        return new ParamNameResolver(configuration,method).getNamedParams(invocation.arguments);
    }
    private static SqlRow mybatisRow(Configuration configuration,Class<?> mapper,Invocation invocation) {
        Object parameters = namedParams(configuration,mapper,invocation);
        BoundSql bound = configuration.getMappedStatement(mapper.getName() + "." + invocation.method).getBoundSql(parameters);
        var values = new TreeMap<Integer,Object>();
        for (int i=0;i<bound.getParameterMappings().size();i++) {
            String property = bound.getParameterMappings().get(i).getProperty();
            Object value = bound.hasAdditionalParameter(property) ? bound.getAdditionalParameter(property)
                    : parameters==null ? null : configuration.getTypeHandlerRegistry().hasTypeHandler(parameters.getClass())
                    ? parameters : configuration.newMetaObject(parameters).getValue(property);
            values.put(i+1,value);
        }
        return new SqlRow(canonical(bound.getSql()),values);
    }
    private record SqlRow(String sql,Map<Integer,Object> bindings) {
        SqlRow { sql=canonical(sql); bindings=Collections.unmodifiableMap(new TreeMap<>(bindings)); }
    }
    private static Optional<SqlRow> savedRow(SqlSample sample) {
        if (sample.sql().isEmpty()) return Optional.empty();
        var bindings = new TreeMap<Integer,Object>();
        for (var binding : sample.bindings()) {
            if (!(binding.keyValue() instanceof SqlValue.Known key)
                    || !(binding.value() instanceof SqlValue.Known value)) return Optional.empty();
            if (!(key.value() instanceof Integer position) || position<=0)
                throw new IllegalStateException("Invalid JDBC position without lossless integer identity: " + key.value());
            if (bindings.containsKey(position)) throw new IllegalStateException("Duplicated binding position");
            bindings.put(position,value.value());
        }
        return Optional.of(new SqlRow(sample.sql().get(),bindings));
    }
    private static List<SqlExtraction> executions(AnalysisResult result) {
        return result.calls().stream().filter(value -> value.callSite().target().getName().startsWith("execute")).toList();
    }
    private static Set<String> jdbcSites(AnalysisResult result) {
        return result.calls().stream().map(value -> value.callSite().caller().orElseThrow().getName() + "/" + value.callSite().target().getName()).collect(Collectors.toSet());
    }
    private static Set<String> rawTexts(AnalysisResult result) { return result.rawSqls().stream().map(value -> canonical(value.text())).collect(Collectors.toSet()); }
    private void compareCodeSites(String project,Analyzed analyzed) throws Exception {
        var inventory=JdbcCallSiteInventory.compare(analyzed.input().getClassOutputDirectory(),analyzed.result());
        metric(project,"code_sql_sites",inventory.total(),inventory.matched(),inventory.extra(),inventory.missing());
        inventory.evidence().forEach(value -> evidence.add("CODE_SITE " + project + " " + value));
    }
    private void compareSet(String project,String metric,Set<String> expected,Set<String> actual) {
        var matched = new HashSet<>(actual); matched.retainAll(expected);
        var extras = new TreeSet<>(actual); extras.removeAll(expected); var missing = new TreeSet<>(expected); missing.removeAll(actual);
        metric(project,metric,expected.size(),matched.size(),extras.size(),0);
        evidence.add("SET " + project + " " + metric + " missing=" + missing + " extras=" + extras);
    }
    private void metric(String project,String metric,int total,int matched,int wrong,int unknown) {
        metrics.add(String.join("\t",project,metric,Integer.toString(total),Integer.toString(matched),Integer.toString(wrong),Integer.toString(unknown)));
    }
    private void assess(String project,AnalysisResult result) {
        var samples = executions(result).stream().flatMap(value -> value.samples().stream()).toList();
        if (project.startsWith("mybatis-")) samples=result.calls().stream().flatMap(value -> value.samples().stream()).toList();
        metric(project,"proven_execution_samples",samples.size(),(int)samples.stream().filter(value -> value.feasibility()==SqlSample.Feasibility.SATISFIABLE).count(),0,
                (int)samples.stream().filter(value -> value.feasibility()!=SqlSample.Feasibility.SATISFIABLE).count());
        int checkedTypes=0, correctTypes=0, unknownTypes=0;
        for (var sample:samples) for (var binding:sample.bindings()) {
            if (!(binding.value() instanceof SqlValue.Known known) || known.value()==null) { unknownTypes++; continue; }
            checkedTypes++;
            String boxed=switch (binding.type()) {
                case "int" -> "java.lang.Integer"; case "long" -> "java.lang.Long"; case "boolean" -> "java.lang.Boolean";
                case "short" -> "java.lang.Short"; case "byte" -> "java.lang.Byte"; case "char" -> "java.lang.Character";
                case "float" -> "java.lang.Float"; case "double" -> "java.lang.Double"; default -> binding.type();
            };
            if (boxed.equals(known.value().getClass().getName())) correctTypes++;
            else evidence.add("BINDING_TYPE " + project + " expected=" + known.value().getClass().getName() + " actual=" + binding.type());
        }
        metric(project,"binding_types",checkedTypes,correctTypes,checkedTypes-correctTypes,unknownTypes);
        evidence.add("STATUS " + project + " " + result.status() + " raw=" + result.rawSqls().size() + " results=" + result.sqlResults().size());
        result.diagnostics().forEach(value -> evidence.add("DIAGNOSTIC " + project + " " + value));
    }
    private static int statementIndex(SqlCallSite site) { return Integer.parseInt(site.location().element().substring(site.location().element().lastIndexOf('@')+1).strip()); }
    private static Optional<Object[]> rootArguments(SqlSample sample,sootup.core.signatures.MethodSignature entry,Class<?>[] types) {
        if (sample.combination().isEmpty()) return Optional.empty();
        var model=sample.combination().get(); var inputs=new LinkedHashMap<ValueTrace.Variable,SqlValue>();
        var values=new ArrayList<>(model.parameters().values());
        model.bindings().forEach(value -> { values.add(value.keyValue()); values.add(value.value()); });
        values.forEach(value -> value.initialValues().forEach(initial -> inputs.put(initial.variable(),initial.value())));
        inputs.putAll(model.inputs());
        Object[] arguments=new Object[types.length]; boolean[] found=new boolean[types.length];
        var pattern=Pattern.compile("@parameter(\\d+):.*");
        for (var input:inputs.entrySet()) if (input.getKey().method().equals(entry)) {
            var matcher=pattern.matcher(input.getKey().expression());
            if (matcher.matches() && input.getValue() instanceof SqlValue.Known known) {
                int index=Integer.parseInt(matcher.group(1)); if (index>=types.length) continue;
                Object value=known.value(); Class<?> type=types[index];
                if (type==int.class && value instanceof Number number) value=number.intValue();
                if (type==long.class && value instanceof Number number) value=number.longValue();
                if (type==boolean.class && value instanceof Number number) value=number.intValue()!=0;
                arguments[index]=value; found[index]=true;
            }
        }
        for (boolean present:found) if (!present) return Optional.empty();
        return Optional.of(arguments);
    }
    /** 仅规范化 SQL 字面量之外的空白，避免掩盖引号内容/LIKE 模式中的空格错误。 */
    static String canonical(String sql) {
        var text=new StringBuilder(); char quote=0; boolean space=false;
        for (int i=0;i<sql.length();i++) {
            char c=sql.charAt(i);
            if (quote!=0) { text.append(c); if (c==quote) { if (i+1<sql.length() && sql.charAt(i+1)==quote) text.append(sql.charAt(++i)); else quote=0; } }
            else if (Character.isWhitespace(c)) space=true;
            else { if (space && !text.isEmpty()) text.append(' '); space=false; text.append(c); if (c=='\'' || c=='"') quote=c; }
        }
        return text.toString();
    }
    static Path root() {
        Path root=Path.of("").toAbsolutePath();
        while (root!=null && !Files.isDirectory(root.resolve("shared-test-resources"))) root=root.getParent();
        return Objects.requireNonNull(root);
    }
    private static Object fallback(Class<?> type) {
        if (type==boolean.class) return false; if (type==int.class) return 0; if (type==long.class) return 0L;
        if (type==void.class || !type.isPrimitive()) return null; throw new UnsupportedOperationException(type.toString());
    }
    /** 捕获真正传到 JDBC 接口的 SQL/绑定；不连接数据库，不改写目标样例。 */
    private static final class JdbcRecorder {
        private final List<SqlRow> rows=new ArrayList<>();
        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args) ->
                    switch (method.getName()) { case "createStatement" -> statement(null,false); case "prepareStatement" -> statement((String)args[0],false);
                        case "prepareCall" -> statement((String)args[0],true); default -> fallback(method.getReturnType()); });
        }
        Object statement(String prepared,boolean callable) {
            Map<Integer,Object> bindings=new TreeMap<>();
            var batch=new ArrayList<SqlRow>();
            Class<?> api=callable ? CallableStatement.class : prepared==null ? Statement.class : PreparedStatement.class;
            return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{api},(proxy,method,args) -> {
                if (method.getName().startsWith("set") && args!=null && args.length>=2 && args[0] instanceof Integer key) {
                    bindings.put(key,method.getName().equals("setNull") ? null : args[1]); return null;
                }
                if (method.getName().equals("clearParameters")) { bindings.clear(); return null; }
                if (method.getName().equals("addBatch")) {
                    batch.add(new SqlRow(prepared==null ? (String)args[0] : prepared,bindings)); return null;
                }
                if (method.getName().equals("clearBatch")) { batch.clear(); return null; }
                if (method.getName().equals("executeBatch") || method.getName().equals("executeLargeBatch")) {
                    int size=batch.size();
                    rows.addAll(batch);
                    batch.clear();
                    if (method.getReturnType()==int[].class) return java.util.stream.IntStream.range(0,size).map(i -> 1).toArray();
                    return java.util.stream.LongStream.range(0,size).map(i -> 1L).toArray();
                }
                if (method.getName().startsWith("execute")) {
                    rows.add(new SqlRow(prepared==null ? (String)args[0] : prepared,bindings));
                    if (method.getReturnType()==ResultSet.class) return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},
                            (rowProxy,rowMethod,rowArgs) -> fallback(rowMethod.getReturnType()));
                    if (method.getReturnType()==int.class) return 1; if (method.getReturnType()==boolean.class) return false;
                    if (method.getReturnType()==long.class) return 1L;
                }
                return fallback(method.getReturnType());
            });
        }
    }
}
