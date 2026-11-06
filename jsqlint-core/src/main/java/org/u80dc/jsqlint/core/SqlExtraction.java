package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.dataflow.RawSqlBuilder;
import org.u80dc.jsqlint.core.node.SqlNode;
import org.u80dc.jsqlint.core.spi.*;
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
        /** 来源、旧模板和 raw SQL 分别记录诊断；整体完整性要求三者均完成。 */
        public boolean complete() { return sourcesComplete() && templateComplete() && rawSql.complete(); }
    }
    public record Binding(String key, ValueTrace keyTrace, ValueTrace value, SourceLocation location) {
        public Binding { Objects.requireNonNull(key); Objects.requireNonNull(keyTrace); Objects.requireNonNull(value); Objects.requireNonNull(location); }
        public boolean complete() { return keyTrace.complete() && value.complete(); }
    }
}
