package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.node.SqlNodeRenderer.RenderedSql;
import org.u80dc.jsqlint.core.node.SqlRewriteStep;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult.Combination;
import org.u80dc.jsqlint.core.spi.SourceLocation;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;

import java.util.*;

/**
 * 所有模块统一的 SQL 样例视图；保留来源、批次、类型、残余模板和求解证据。
 * JDBC 的 ? 与绑定值分开保存，不将绑定参数拼回 SQL 字符串。
 * @author u80dc
 */
public record SqlSample(SqlResult source, Optional<SourceLocation> batchEntry, RenderedSql rendered,
                        List<Binding> bindings, Feasibility feasibility, SqlAssessment assessment,
                        List<String> issues, SqlSampleEvidence evidence) {
    /** 将原有分散的证据参数聚合到同一不可变对象。 */
    public SqlSample(SqlResult source, Optional<SourceLocation> batchEntry, RenderedSql rendered,
                     List<Binding> bindings, Feasibility feasibility, SqlAssessment assessment,
                     List<String> issues, Optional<RawSql.Variant> rawSql, Optional<Combination> combination,
                     Optional<SqlDefinition> definition, List<SqlRewriteStep> rewrites) {
        this(source, batchEntry, rendered, bindings, feasibility, assessment, issues,
                new SqlSampleEvidence(rawSql, combination, definition, rewrites));
    }
    public SqlSample(SqlResult source,Optional<SourceLocation> batchEntry,RenderedSql rendered,List<Binding> bindings,
                     Feasibility feasibility,SqlAssessment assessment,List<String> issues,Optional<RawSql.Variant> rawSql,
                     Optional<Combination> combination,Optional<SqlDefinition> definition) {
        this(source,batchEntry,rendered,bindings,feasibility,assessment,issues,rawSql,combination,definition,List.of());
    }
    public SqlSample {
        Objects.requireNonNull(source); Objects.requireNonNull(batchEntry); Objects.requireNonNull(rendered);
        bindings = List.copyOf(bindings); Objects.requireNonNull(feasibility); Objects.requireNonNull(assessment);
        issues = List.copyOf(issues); Objects.requireNonNull(evidence);
    }
    public SqlSample(SqlResult source, Optional<SourceLocation> batchEntry, RenderedSql rendered,
                     List<Binding> bindings, Feasibility feasibility, SqlAssessment assessment,
                     List<String> issues, Optional<RawSql.Variant> rawSql, Optional<Combination> combination) {
        this(source, batchEntry, rendered, bindings, feasibility, assessment, issues, rawSql, combination, Optional.empty());
    }
    public String moduleId() { return source.moduleId(); }
    public SourceLocation location() { return source.location(); }
    public Optional<String> sql() { return rendered.sql(); }
    public Optional<RawSql.Variant> rawSql() { return evidence.rawSql(); }
    public Optional<Combination> combination() { return evidence.combination(); }
    public Optional<SqlDefinition> definition() { return evidence.definition(); }
    public List<SqlRewriteStep> rewrites() { return evidence.rewrites(); }

    /** SATISFIABLE 只表示该组合在支持的路径模型内可行；UNVERIFIED 表示没有求解路径。 */
    public enum Feasibility { SATISFIABLE, UNVERIFIED, UNKNOWN }

    /** keyValue 为实际位置/名称，key 保留来源标识；已知 null 与 Unknown 均原样保存。 */
    public record Binding(String key, String type, SqlValue keyValue, SqlValue value, SourceLocation location,
                          String options, Optional<ValueTrace> trace) {
        public Binding {
            Objects.requireNonNull(key); Objects.requireNonNull(type); Objects.requireNonNull(keyValue);
            Objects.requireNonNull(value); Objects.requireNonNull(location);
            Objects.requireNonNull(options); Objects.requireNonNull(trace);
        }
        public Binding(String key, String type, SqlValue keyValue, SqlValue value, SourceLocation location) {
            this(key, type, keyValue, value, location, "", Optional.empty());
        }
    }
}
