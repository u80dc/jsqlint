package org.u80dc.jsqlint.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.parameter.ParameterValueInferenceResult;
import java.util.*;
import java.util.function.Function;

/** SQL 结果的有界日志视图；用节点编号保留共享关系，不枚举全部依赖路径。 @author u80dc */
final class SqlAnalysisLogger {
    private static final Logger LOG = LoggerFactory.getLogger(SqlAnalysisLogger.class);
    private static final int MAX_LINES = 256, MAX_NODES = 128, MAX_EDGES = 256, MAX_ITEMS = 8, MAX_TEXT = 1024;
    private final Runnable checkpoint;
    SqlAnalysisLogger(Runnable checkpoint) { this.checkpoint = checkpoint; }

    void extraction(SqlExtraction extraction) {
        if (!LOG.isInfoEnabled()) return;
        var budget = new Budget();
        log(budget, "SQL 提取: module={}, call={}, chain={}, statements={}, issues={}", extraction.moduleId(),
                extraction.callSite(), head(extraction.callChain()), extraction.statements().size(), strings(extraction.issues()));
        for (var statement : extraction.statements()) {
            if (!budget.available()) break;
            if (statement.definition().isPresent()) {
                var definition = statement.definition().get();
                log(budget, "SQL 定义: id={}, location={}, issues={}", text(definition.id()), definition.location(), strings(definition.issues()));
            }
            if (statement.templateInference().isPresent()) {
                var inference = statement.templateInference().get();
                log(budget, "模板实参: aliases={}, models={}", inference.aliases().size(), inference.models().size());
                for (var entry : inference.sources().entrySet()) {
                    if (!budget.available()) break;
                    printTrace("templateInput[" + text(entry.getKey()) + "]", entry.getValue(), budget);
                }
                for (var parameter : inference.parameters().values()) {
                    if (!budget.available()) break;
                    printInference(parameter, budget);
                }
            }
            log(budget, "SQL 来源: batch={}, bindings={}, issues={}, templateComplete={}, templateIssues={}",
                    statement.batchEntry(), statement.bindings().size(), strings(statement.issues()), statement.templateComplete(), strings(statement.templateIssues()));
            for (int i = 0; i < statement.rawSql().variants().size() && budget.available(); i++) {
                var variant = statement.rawSql().variants().get(i);
                String label = "rawSql[" + (i + 1) + "]";
                log(budget, "Raw SQL: variant={}, sql={}, placeholders={}, issues={}", label, text(variant.text()),
                        variant.placeholders().size(), strings(statement.rawSql().issues()));
                variant.trace().ifPresent(trace -> printTrace(label, trace, budget));
                // 查询结果 record 的结构哈希可能递归遍历来源，日志只按已保存对象的身份去重。
                Set<ParameterValueInferenceResult> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                int inspected = 0;
                for (var slot : variant.placeholders()) {
                    if (!budget.available()) break;
                    if (inspected >= MAX_ITEMS) {
                        log(budget, "传播证据截断: variant={}, 省略 {} 项槽位推断", label,
                                variant.placeholders().size() - inspected); break;
                    }
                    inspected++;
                    if (seen.add(slot.inference())) printInference(slot.inference(), budget);
                }
                if (variant.jointInference().isPresent() && budget.available()) {
                    var joint = variant.jointInference().get();
                    log(budget, "SQL 联合路径: variant={}, status={}, combinations={}, issues={}",
                            label, joint.status(), joint.combinations().size(), strings(joint.issues()));
                    for (int j = 0; j < joint.combinations().size() && budget.available(); j++) {
                        var combination = joint.combinations().get(j);
                        log(budget, "SQL 联合组合: variant={}, combination={}, status={}, parameters={}, bindings={}, conditions={}, issues={}",
                                label, j + 1, combination.status(),
                                preview(combination.parameters().entrySet(),
                                        entry -> text(entry.getKey()) + "=" + value(entry.getValue().value())),
                                preview(combination.bindings(),
                                        binding -> value(binding.keyValue().value()) + "=" + value(binding.value().value())),
                                combination.conditions().size(), strings(combination.issues()));
                    }
                }
            }
            statement.sql().ifPresent(trace -> printTrace("sql", trace, budget));
            for (var binding : statement.bindings()) {
                if (!budget.available()) break;
                printTrace("binding[" + text(binding.key()) + "].key", binding.keyTrace(), budget);
                printTrace("binding[" + text(binding.key()) + "].value", binding.value(), budget);
                printInference(binding.inference(), budget);
            }
        }
        if (!budget.available()) LOG.info("传播证据截断: 本次提取最多输出 {} 行，后续证据未展开", MAX_LINES);
    }

    private void printInference(ParameterValueInferenceResult result, Budget budget) {
        if (!budget.available()) return;
        log(budget, "参数推断: name={}, type={}, values={}, issues={}", text(result.name()), result.type(), result.possibleValues().size(), strings(result.issues()));
        for (var value : result.possibleValues()) {
            if (!budget.available()) break;
            log(budget, "参数推断值: name={}, value={}, initialValues={}, issues={}",
                    text(result.name()), value(value.value()), preview(value.initialValues(),
                            initial -> variable(initial.variable()) + "=" + value(initial.value()) + "/generated=" + initial.generated()),
                    strings(value.issues()));
            printTrace("parameter[" + text(result.name()) + "]", value.propagationPath(), budget);
        }
    }

    private void printTrace(String label, ValueTrace trace, Budget budget) {
        if (!budget.available()) return;
        log(budget, "变量调用链: variable={}, source={}, chain={}", label, variable(trace.variable()), head(trace.callChain()));
        Map<ValueTrace.Node, Integer> ids = new IdentityHashMap<>();
        Deque<ValueTrace.Node> pending = new ArrayDeque<>();
        ids.put(trace.root(), 0); pending.add(trace.root());
        int edges = 0;
        boolean truncated = false;
        while (!pending.isEmpty() && budget.available()) {
            checkpoint.run();
            var node = pending.removeFirst();
            var inputs = new ArrayList<Integer>();
            for (var input : node.inputs()) {
                checkpoint.run();
                if (edges >= MAX_EDGES) { truncated = true; break; }
                edges++;
                Integer id = ids.get(input);
                if (id == null) {
                    if (ids.size() >= MAX_NODES) { truncated = true; break; }
                    id = ids.size(); ids.put(input, id); pending.addLast(input);
                }
                inputs.add(id);
            }
            log(budget, "逆向数据链: variable={}, node={}, source={}, kind={}, detail={}, inputs={}",
                    label, ids.get(node), variable(node.variable()), node.kind(), text(node.detail()), head(inputs));
            if (truncated) break;
        }
        if (truncated || !pending.isEmpty())
            log(budget, "传播证据截断: variable={}, nodeLimit={}, edgeLimit={}, 后续节点未展开", label, MAX_NODES, MAX_EDGES);
    }

    private void log(Budget budget, String format, Object... values) {
        if (budget.available()) { checkpoint.run(); budget.lines++; LOG.info(format, values); }
    }
    private static String variable(ValueTrace.Variable value) {
        return text(Objects.toString(value.method())) + " @" + value.statementIndex() + " " + text(value.expression()) + " : " + value.type();
    }
    private static String text(String value) { return value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT) + "…"; }
    private static String value(SqlValue value) {
        return value instanceof SqlValue.Unknown unknown ? "Unknown[reason=" + text(unknown.reason()) + "]" : text(value.toString());
    }
    private static List<Object> strings(List<String> values) { return preview(values, SqlAnalysisLogger::text); }
    private static <T> List<Object> head(List<T> values) { return preview(values, Function.identity()); }
    private static <T> List<Object> preview(Collection<T> values, Function<? super T, ?> summary) {
        int size = values.size();
        boolean omitted = size > MAX_ITEMS;
        int shown = omitted ? MAX_ITEMS - 1 : size;
        var result = new ArrayList<Object>(Math.min(size, MAX_ITEMS));
        var iterator = values.iterator();
        for (int i = 0; i < shown; i++) result.add(summary.apply(iterator.next()));
        if (omitted) result.add("…（省略 " + (size - shown) + " 项）");
        return result;
    }
    private static final class Budget {
        int lines;
        boolean available() { return lines < MAX_LINES; }
    }
}
