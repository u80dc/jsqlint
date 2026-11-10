package org.u80dc.jsqlint.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.parameter.ParameterValueInferenceResult;
import java.util.*;

/** SQL 结果及传播证据的日志视图，不参与提取或完整性判定。 @author u80dc */
final class SqlAnalysisLogger {
    private static final Logger LOG = LoggerFactory.getLogger(SqlAnalysisLogger.class);
    private final Runnable checkpoint;
    SqlAnalysisLogger(Runnable checkpoint) { this.checkpoint = checkpoint; }
    void extraction(SqlExtraction extraction) {
        if (!LOG.isInfoEnabled()) return;
        LOG.info("SQL 提取: module={}, call={}, chain={}, complete={}, issues={}", extraction.moduleId(),
                extraction.callSite(), extraction.callChain(), extraction.complete(), extraction.issues());
        for (var statement : extraction.statements()) {
            statement.definition().ifPresent(definition -> LOG.info("SQL 定义: id={}, location={}, metadata={}, issues={}",
                    definition.id(), definition.location(), definition.metadata(), definition.issues()));
            statement.templateInference().ifPresent(inference -> {
                LOG.info("模板实参: aliases={}, fallback={}, models={}", inference.aliases(), inference.fallback(), inference.models().size());
                inference.sources().forEach((name, trace) -> printTrace("templateInput[" + name + "]", trace));
                inference.parameters().values().forEach(this::printInference);
            });
            LOG.info("SQL 来源: batch={}, bindings={}, issues={}, templateComplete={}, templateIssues={}",
                    statement.batchEntry(), statement.bindings().size(), statement.issues(), statement.templateComplete(), statement.templateIssues());
            for (int i = 0; i < statement.rawSql().variants().size(); i++) {
                var variant = statement.rawSql().variants().get(i);
                String label = "rawSql[" + (i + 1) + "]";
                LOG.info("Raw SQL: variant={}, sql={}, placeholders={}, complete={}, issues={}", label, variant.text(),
                        variant.placeholders().stream().map(RawSql.Placeholder::name).toList(), statement.rawSql().complete(), statement.rawSql().issues());
                // 同一调用点可能有多条相同文本，使用候选编号关联各自选中的传播分支。
                variant.trace().ifPresent(trace -> printTrace(label, trace));
                variant.placeholders().stream().map(RawSql.Placeholder::inference).distinct().forEach(this::printInference);
                variant.jointInference().ifPresent(joint -> {
                    if (!LOG.isInfoEnabled()) return;
                    LOG.info("SQL 联合路径: variant={}, status={}, combinations={}, issues={}",
                            label, joint.status(), joint.combinations().size(), joint.issues());
                    // 组合级未知原因与参数值一起输出，避免汇总 issues 为空时无法解释 UNKNOWN。
                    for (int combinationIndex = 0; combinationIndex < joint.combinations().size(); combinationIndex++) {
                        var combination = joint.combinations().get(combinationIndex);
                        LOG.info("SQL 联合组合: variant={}, combination={}, status={}, parameters={}, bindings={}, conditions={}, issues={}",
                                label, combinationIndex + 1, combination.status(),
                                combination.parameters().entrySet().stream()
                                        .map(entry -> entry.getKey() + "=" + entry.getValue().value()).toList(),
                                combination.bindings().stream()
                                        .map(binding -> binding.keyValue().value() + "=" + binding.value().value()).toList(),
                                combination.conditions().size(), combination.issues());
                    }
                });
            }
            statement.sql().ifPresent(trace -> printTrace("sql", trace));
            for (var binding : statement.bindings()) {
                printTrace("binding[" + binding.key() + "].key", binding.keyTrace());
                printTrace("binding[" + binding.key() + "].value", binding.value());
                printInference(binding.inference());
            }
        }
    }
    private void checkpoint() { checkpoint.run(); }
    private void printInference(ParameterValueInferenceResult result) {
        LOG.info("参数推断: name={}, type={}, complete={}, issues={}", result.name(), result.type(), result.complete(), result.issues());
        for (var value : result.possibleValues()) {
            LOG.info("参数推断值: name={}, value={}, sampled={}, initialValues={}, issues={}",
                    result.name(), value.value(), value.sampled(), value.initialValues(), value.issues());
            printTrace("parameter[" + result.name() + "]", value.propagationPath());
        }
    }

    private void printTrace(String label, ValueTrace trace) {
        if (!LOG.isInfoEnabled()) return;
        LOG.info("变量调用链: variable={}, source={}, chain={}, complete={}", label, trace.variable(), trace.callChain(), trace.complete());
        printTracePaths(label, trace.root(), new ArrayList<>());
    }
    private void printTracePaths(String label, ValueTrace.Node node, List<String> path) {
        checkpoint();
        var variable = node.variable();
        path.add(variable.method() + " @" + variable.statementIndex() + " " + variable.expression() + " [" + node.kind() + ": " + node.detail() + "]");
        if (node.inputs().isEmpty()) LOG.info("逆向数据链: variable={}, path={}", label, String.join(" <- ", path));
        else for (var child : node.inputs()) printTracePaths(label, child, path);
        path.removeLast();
    }

}
