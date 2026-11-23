package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.node.SqlRewriteStep;
import org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult.Combination;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 样例关联的详细证据；共享已冻结的候选、路径模型和定义，不复制或重新计算来源。 */
public record SqlSampleEvidence(Optional<RawSql.Variant> rawSql, Optional<Combination> combination,
                                Optional<SqlDefinition> definition, List<SqlRewriteStep> rewrites) {
    public SqlSampleEvidence {
        Objects.requireNonNull(rawSql);
        Objects.requireNonNull(combination);
        Objects.requireNonNull(definition);
        rewrites = List.copyOf(rewrites);
    }

    public static SqlSampleEvidence empty() {
        return new SqlSampleEvidence(Optional.empty(), Optional.empty(), Optional.empty(), List.of());
    }
}
