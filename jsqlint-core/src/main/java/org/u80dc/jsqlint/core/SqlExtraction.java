package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.dataflow.RawSqlBuilder;
import org.u80dc.jsqlint.core.node.SqlNode;
import org.u80dc.jsqlint.core.spi.*;
import org.u80dc.jsqlint.core.parameter.ParameterValueInferenceResult;
import org.u80dc.jsqlint.core.parameter.ParameterValueInferencer;
import java.util.*;

/** 单个 SQL 调用点在一个调用上下文中的结果；关闭 SootUp 输入后仍可读取。 @author u80dc */
public record SqlExtraction(String moduleId, SqlCallSite callSite, List<SqlCallSite> callChain,
                            List<Statement> statements, List<String> issues) {
    public SqlExtraction {
        Objects.requireNonNull(moduleId); Objects.requireNonNull(callSite); callChain = List.copyOf(callChain);
        statements = List.copyOf(statements); issues = List.copyOf(issues);
    }
    public boolean complete() { return issues.isEmpty() && statements.stream().allMatch(Statement::complete); }
    public record Statement(Optional<ValueTrace> sql, SqlNode template, List<Binding> bindings,
                            Optional<SourceLocation> batchEntry, List<String> issues, List<String> templateIssues, RawSql rawSql) {
        public Statement {
            Objects.requireNonNull(sql); Objects.requireNonNull(template); bindings = List.copyOf(bindings);
            Objects.requireNonNull(batchEntry); issues = List.copyOf(issues); templateIssues = List.copyOf(templateIssues); Objects.requireNonNull(rawSql);
            // Statement 是绑定快照的来源，确保顶层 rawSqls 和逐语句访问得到相同的 SQL/参数关联。
            rawSql = rawSql.withBindings(bindings);
        }
        public Statement(Optional<ValueTrace> sql, SqlNode template, List<Binding> bindings,
                         Optional<SourceLocation> batchEntry, List<String> issues, List<String> templateIssues) {
            this(sql, template, bindings, batchEntry, issues, templateIssues,
                    sql.map(trace -> RawSqlBuilder.build(trace, () -> {}))
                            .orElseGet(() -> new RawSql(List.of(), List.of("SQL source is unavailable"))));
        }
        /** 兼容调用方已自行构造完整模板的入口。 */
        public Statement(Optional<ValueTrace> sql, SqlNode template, List<Binding> bindings,
                         Optional<SourceLocation> batchEntry, List<String> issues) {
            this(sql, template, bindings, batchEntry, issues, List.of());
        }
        public boolean sourcesComplete() { return issues.isEmpty() && sql.isPresent() && sql.get().complete() && bindings.stream().allMatch(Binding::complete); }
        /** 完整表示恢复过程未遗漏支持范围内的结构，不要求外部参数已有运行时取值。 */
        public boolean templateComplete() { return templateIssues.isEmpty(); }
        /** 参数重演单独判断；没有绑定值或拼接槽位时无需推断。 */
        public boolean inferenceComplete() {
            return bindings.stream().allMatch(binding -> binding.inference().complete())
                    && rawSql.variants().stream().flatMap(variant -> variant.parameters().stream())
                    .allMatch(ParameterValueInferenceResult::complete);
        }
        /** SQL 提取完整性要求来源、旧模板和 raw SQL 均完成；样例重演另见 inferenceComplete。 */
        public boolean complete() { return sourcesComplete() && templateComplete() && rawSql.complete(); }
    }
    public record Binding(String key, ValueTrace keyTrace, ValueTrace value, SourceLocation location, ParameterValueInferenceResult inference) {
        public Binding { Objects.requireNonNull(key); Objects.requireNonNull(keyTrace); Objects.requireNonNull(value); Objects.requireNonNull(location); Objects.requireNonNull(inference); }
        public Binding(String key, ValueTrace keyTrace, ValueTrace value, SourceLocation location) {
            this(key, keyTrace, value, location, new ParameterValueInferenceResult(ParameterValueInferencer.variableName(value.root()),
                    value.variable().type(), List.of(), List.of("Parameter not inferred")));
        }
        /** 只判断溯源完整性；推断是否成功通过 inference.complete() 获取。 */
        public boolean complete() { return keyTrace.complete() && value.complete(); }
    }
}
