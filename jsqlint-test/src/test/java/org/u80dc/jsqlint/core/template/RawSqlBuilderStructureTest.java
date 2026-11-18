package org.u80dc.jsqlint.core.template;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.RawSql;
import org.u80dc.jsqlint.core.SqlRecoveryBudgets;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import sootup.java.core.JavaIdentifierFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.dataflow.ValueTrace.*;

/** 使用可重复的深链/共享 DAG 规模验证，避免依赖机器速度的毫秒断言。 */
class RawSqlBuilderStructureTest {
    @Test
    void sharedDagIsIndexedByUniqueNodesWithoutRecursiveHashing() {
        for (int depth : List.of(16, 32, 64)) {
            Node root = sharedDag(depth);
            AtomicInteger checkpoints = new AtomicInteger();
            RawSql raw = assertDoesNotThrow(() -> build(root, SqlRecoveryBudgets.defaults(), checkpoints));
            assertEquals(1, raw.variants().size());
            assertSame(root, raw.variants().getFirst().placeholders().getFirst().source());
            assertTrue(assertDoesNotThrow(root::complete), "来源完整性检查同样只遍历唯一节点");
            assertTrue(raw.issues().stream().noneMatch(issue -> issue.contains("indexing budget")), raw.issues().toString());
            assertTrue(checkpoints.get() <= 8 * (depth + 1) + 20,
                    "工作量应随共享节点数增长，而非随 2^depth 条展开路径增长: " + checkpoints.get());
        }
    }

    @Test
    void deepResidualSourceDoesNotUseTheJavaCallStackForItsKey() {
        Node root = opaque(0, "leaf");
        for (int depth = 1; depth <= 12000; depth++) root = node(depth, "opaque", Operation.OPAQUE, "deep source", root);
        Node source = root;
        var raw = assertDoesNotThrow(() -> build(source, SqlRecoveryBudgets.defaults().withSteps(20000), new AtomicInteger()));
        assertSame(source, raw.variants().getFirst().placeholders().getFirst().source());
        assertTrue(assertDoesNotThrow(source::complete), "深来源的结果完整性检查不递归调用 Java 栈");
        assertTrue(raw.issues().stream().noneMatch(issue -> issue.contains("indexing budget")), raw.issues().toString());
    }

    @Test
    void sharedDagCompletenessStillReportsEveryIncompleteSourceKind() {
        for (Kind incomplete : List.of(Kind.CYCLE, Kind.LIMIT, Kind.UNRESOLVED, Kind.FIELD, Kind.EXTERNAL_CALL)) {
            Node original = opaque(0, "input");
            Node child = new Node(original.variable(), incomplete, "incomplete source", List.of(), original.computation());
            for (int i = 1; i <= 64; i++) child = node(i, "opaque", Operation.OPAQUE, "shared source", child, child);
            Node root = child;
            assertFalse(assertDoesNotThrow(root::complete), incomplete.name());
        }
    }

    @Test
    void equalSeparatelyConstructedSourcesReuseSlotsAndStillHaveRecordValueSemantics() {
        Node first = sharedDag(12), second = sharedDag(12);
        assertNotSame(first, second);
        // 小规模直接检查对外的结构相等语义；构造器和 API 仍保留 record 行为。
        assertEquals(opaque(7, "value"), opaque(7, "value"));
        assertEquals(opaque(7, "value").hashCode(), opaque(7, "value").hashCode());
        var raw = build(node(100, "concat", Operation.CONCAT, "concat", first, second), SqlRecoveryBudgets.defaults(), new AtomicInteger());
        var slots = raw.variants().getFirst().placeholders();
        assertEquals(2, slots.size());
        assertEquals(slots.getFirst().name(), slots.getLast().name(), "独立实例的同一结构来源仍复用槽位");
    }

    @Test
    void sourceLocationsAndTransformationsRemainSeparate() {
        Node original = opaque(1, "value");
        Node anotherLocation = opaque(2, "value");
        Node transformed = node(1, "value", Operation.CALL, "trimmed value", original);
        var raw = build(node(100, "concat", Operation.CONCAT, "concat", original, anotherLocation, transformed),
                SqlRecoveryBudgets.defaults(), new AtomicInteger());
        var slots = raw.variants().getFirst().placeholders();
        assertEquals(3, slots.stream().map(RawSql.Placeholder::name).distinct().count());
        assertSame(original, slots.get(0).source());
        assertSame(anotherLocation, slots.get(1).source());
        assertSame(transformed, slots.get(2).source());
    }

    @Test
    void sameSqlTextKeepsDifferentEvidenceAndCollapsesOnlyEqualEvidence() {
        Node first = node(1, "sql", Operation.LITERAL, "SELECT 1");
        Node equal = node(1, "sql", Operation.LITERAL, "SELECT 1");
        Node anotherLocation = node(2, "sql", Operation.LITERAL, "SELECT 1");
        var raw = build(node(100, "choice", Operation.ALTERNATIVES, "choice", first, equal, anotherLocation),
                SqlRecoveryBudgets.defaults(), new AtomicInteger());
        assertEquals(List.of("SELECT 1", "SELECT 1"), raw.variants().stream().map(RawSql.Variant::text).toList());
        assertEquals(2, raw.variants().stream().map(variant -> variant.trace().orElseThrow().root().inputs().getFirst().variable().statementIndex()).distinct().count());
    }

    @Test
    void fingerprintBudgetRetainsTheOriginalSourceAndReportsTruncation() {
        Node source = sharedDag(32);
        var raw = build(source, SqlRecoveryBudgets.defaults().withSteps(8), new AtomicInteger());
        assertFalse(raw.complete());
        assertTrue(raw.issues().stream().anyMatch(issue -> issue.contains("source indexing budget")), raw.issues().toString());
        assertSame(source, raw.variants().getFirst().placeholders().getFirst().source());
    }

    private static Node sharedDag(int depth) {
        Node child = opaque(0, "leaf");
        for (int i = 1; i <= depth; i++) child = node(i, "opaque", Operation.OPAQUE, "shared source", child, child);
        return child;
    }
    private static Node opaque(int statement, String expression) { return node(statement, expression, Operation.OPAQUE, "unmodeled input"); }
    private static Node node(int statement, String expression, Operation operation, String detail, Node... children) {
        var method = JavaIdentifierFactory.getInstance().getMethodSignature("example.Sample", "query", "java.lang.String", List.of());
        var variable = new Variable(method, statement, expression, "java.lang.String");
        return new Node(variable, Kind.EXPRESSION, detail, List.of(children), new Computation(operation, detail));
    }
    private static RawSql build(Node root, SqlRecoveryBudgets budgets, AtomicInteger checkpoints) {
        return RawSqlBuilder.build(new ValueTrace(root.variable(), List.of(), root), budgets, checkpoints::incrementAndGet);
    }
}
