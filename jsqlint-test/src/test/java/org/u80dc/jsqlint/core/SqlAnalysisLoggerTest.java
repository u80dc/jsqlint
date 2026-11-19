package org.u80dc.jsqlint.core;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.StaticTextSqlNode;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.parameter.*;
import org.u80dc.jsqlint.core.spi.*;
import sootup.java.core.JavaIdentifierFactory;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Isolated("Temporary logger level and appender")
class SqlAnalysisLoggerTest {
    @Test void aSharedGraphIsLoggedOncePerUniqueNode() {
        var node = node(List.of());
        for (int i = 0; i < 30; i++) node = node(List.of(node, node));
        var messages = log(node, Level.INFO, 500);
        var graph = messages.stream().filter(message -> message.startsWith("逆向数据链:")).toList();
        assertEquals(31, graph.size());
        assertTrue(graph.getFirst().contains("inputs=[1, 1]"));
        assertTrue(messages.stream().noneMatch(message -> message.contains("传播证据截断")));
    }

    @Test void deepAndWideGraphsAreTruncatedWithoutRecursiveTraversal() {
        var deep = node(List.of());
        for (int i = 0; i < 12000; i++) deep = node(List.of(deep));
        for (var graph : List.of(deep, node(Collections.nCopies(12000, node(List.of()))))) {
            var messages = log(graph, Level.INFO, 800);
            assertTrue(messages.size() <= 132);
            assertTrue(messages.stream().anyMatch(message -> message.contains("传播证据截断")));
            if (graph.inputs().size() > 8) {
                String preview = messages.stream().filter(message -> message.startsWith("逆向数据链:")).findFirst().orElseThrow();
                assertTrue(preview.contains("省略"), preview);
            }
        }
    }

    @Test void disablingInfoAvoidsEvidenceTraversal() {
        assertTrue(log(node(List.of()), Level.WARN, 0).isEmpty());
    }

    @Test void everyShortenedListReportsItsOmittedCount() {
        var root = node(List.of());
        var site = new SqlCallSite(root.variable().method(), Optional.empty(),
                new SourceLocation(URI.create("memory:/logger"), "query"));
        var chain = new ArrayList<SqlCallSite>();
        var issues = new ArrayList<String>();
        var initials = new ArrayList<InferredParameterValue.InitialValue>();
        for (int i = 0; i < 10; i++) {
            chain.add(new SqlCallSite(root.variable().method(), Optional.empty(),
                    new SourceLocation(URI.create("memory:/chain" + i), "chain" + i)));
            issues.add("issue" + i);
            initials.add(new InferredParameterValue.InitialValue(
                    new ValueTrace.Variable(root.variable().method(), i, "initial" + i, "int"), new SqlValue.Known(i), false));
        }
        var parameters = new LinkedHashMap<String, InferredParameterValue>();
        var bindings = new ArrayList<SqlExtraction.Binding>();
        var selected = new ArrayList<JointSqlInferenceResult.BindingValue>();
        var slots = new ArrayList<RawSql.Placeholder>();
        var sql = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            String name = "p" + i;
            var variable = new ValueTrace.Variable(root.variable().method(), i, name, "java.lang.String");
            var source = new ValueTrace.Node(variable, ValueTrace.Kind.CONSTANT, "v" + i, List.of(),
                    new ValueTrace.Computation(ValueTrace.Operation.LITERAL, "v" + i));
            var trace = new ValueTrace(variable, chain, source);
            var value = new InferredParameterValue(new SqlValue.Known("v" + i), trace, initials, issues);
            var inference = new ParameterValueInferenceResult(name, "java.lang.String", List.of(value), issues);
            parameters.put(name, value);
            String key = Integer.toString(i + 1);
            var keyVariable = new ValueTrace.Variable(root.variable().method(), i, "key" + i, "int");
            var keySource = new ValueTrace.Node(keyVariable, ValueTrace.Kind.CONSTANT, key, List.of(),
                    new ValueTrace.Computation(ValueTrace.Operation.LITERAL, key));
            var keyTrace = new ValueTrace(keyVariable, chain, keySource);
            var keyValue = new InferredParameterValue(new SqlValue.Known(i + 1), keyTrace, List.of(), List.of());
            bindings.add(new SqlExtraction.Binding(key, keyTrace, trace, site.location(), inference));
            selected.add(new JointSqlInferenceResult.BindingValue(key, keyValue, value));
            int start = sql.length(); sql.append("${").append(name).append('}');
            slots.add(new RawSql.Placeholder(name, start, sql.length(), source, inference));
        }
        var combination = new JointSqlInferenceResult.Combination(JointSqlInferenceResult.Status.UNKNOWN,
                parameters, selected, List.of(), Map.of(), issues);
        var joint = new JointSqlInferenceResult(JointSqlInferenceResult.Status.UNKNOWN, List.of(combination), issues);
        var variant = new RawSql.Variant(sql.toString(), slots, Optional.empty(), bindings, Optional.of(joint));
        var statement = new SqlExtraction.Statement(Optional.empty(), new StaticTextSqlNode(sql.toString()), bindings,
                Optional.empty(), issues, issues, new RawSql(List.of(variant), issues));
        var messages = log(new SqlExtraction("logger-test", site, chain, List.of(statement), issues), Level.INFO, 2000);

        String extraction = messages.stream().filter(message -> message.startsWith("SQL 提取:")).findFirst().orElseThrow();
        assertTrue(extraction.contains("chain6")); assertFalse(extraction.contains("chain7"));
        assertTrue(extraction.contains("issue6")); assertFalse(extraction.contains("issue7"));
        assertEquals(2, extraction.split("省略 3 项", -1).length - 1, extraction);
        String model = messages.stream().filter(message -> message.startsWith("SQL 联合组合:")).findFirst().orElseThrow();
        assertTrue(model.contains("p6=")); assertFalse(model.contains("p7="));
        assertEquals(2, model.split("省略 2 项", -1).length - 1, model);
        String inferred = messages.stream().filter(message -> message.startsWith("参数推断值: name=p0,")).findFirst().orElseThrow();
        assertTrue(inferred.contains("initial6")); assertFalse(inferred.contains("initial7"));
        assertEquals(2, inferred.split("省略 3 项", -1).length - 1, inferred);
        assertTrue(messages.stream().anyMatch(message -> message.contains("省略 1 项槽位推断")));
    }

    @Test void oneExtractionCannotProduceMoreThanTheEvidenceLineBudget() {
        var root = node(List.of());
        var trace = new ValueTrace(root.variable(), List.of(), root);
        var statement = new SqlExtraction.Statement(Optional.of(trace), new StaticTextSqlNode("SELECT 1"), List.of(),
                Optional.empty(), List.of(), List.of(), new RawSql(List.of(), List.of()));
        var site = new SqlCallSite(trace.variable().method(), Optional.empty(),
                new SourceLocation(URI.create("memory:/logger"), "query"));
        var messages = log(new SqlExtraction("logger-test", site, List.of(), Collections.nCopies(1000, statement), List.of()), Level.INFO, 1000);
        // The final omission notice is separate from the 256 evidence lines.
        assertTrue(messages.size() <= 257);
        assertTrue(messages.getLast().contains("本次提取最多输出 256 行"));
    }

    private static ValueTrace.Node node(List<ValueTrace.Node> inputs) {
        var method = JavaIdentifierFactory.getInstance().getMethodSignature("example.Logger", "query", "void", List.of());
        return new ValueTrace.Node(new ValueTrace.Variable(method, 0, "sql", "java.lang.String"),
                ValueTrace.Kind.EXPRESSION, "shared", inputs);
    }
    private static List<String> log(ValueTrace.Node root, Level level, int maxChecks) {
        var trace = new ValueTrace(root.variable(), List.of(), root);
        var statement = new SqlExtraction.Statement(Optional.of(trace), new StaticTextSqlNode("SELECT 1"), List.of(),
                Optional.empty(), List.of(), List.of(), new RawSql(List.of(), List.of()));
        var site = new SqlCallSite(root.variable().method(), Optional.empty(), new SourceLocation(URI.create("memory:/logger"), "query"));
        return log(new SqlExtraction("logger-test", site, List.of(), List.of(statement), List.of()), level, maxChecks);
    }
    private static List<String> log(SqlExtraction extraction, Level level, int maxChecks) {
        var logger = (Logger) LoggerFactory.getLogger(SqlAnalysisLogger.class);
        var old = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>(); appender.start();
        logger.addAppender(appender); logger.setLevel(level);
        try {
            var checks = new AtomicInteger();
            new SqlAnalysisLogger(() -> assertTrue(checks.incrementAndGet() <= maxChecks, "Evidence work must stay bounded")).extraction(extraction);
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        } finally { logger.detachAppender(appender); appender.stop(); logger.setLevel(old); }
    }
}
