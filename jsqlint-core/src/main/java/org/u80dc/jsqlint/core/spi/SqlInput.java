package org.u80dc.jsqlint.core.spi;

import org.u80dc.jsqlint.core.node.SqlNode;
import org.u80dc.jsqlint.core.SqlDefinition;
import org.u80dc.jsqlint.core.dataflow.DataFlowAnalysis;
import sootup.core.jimple.common.Value;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.java.core.JavaSootMethod;

import java.util.Objects;
import java.util.function.Function;

/**
 * 框架适配后的通用 SQL 来源，由 core 负责值追踪、推断及渲染。
 *
 * @author u80dc
 */
public sealed interface SqlInput {
    /** 在共享代码索引就绪后解析计算型定义；结果不持有该回调或分析输入。 */
    record ComputedDefinition(Function<DataFlowAnalysis, SqlDefinition> resolve) implements SqlInput {
        public ComputedDefinition { Objects.requireNonNull(resolve); }
    }
    /** 有独立来源与标识的模板定义；不要求存在 Java SQL 字符串。 */
    record Definition(SqlDefinition definition) implements SqlInput {
        public Definition { Objects.requireNonNull(definition, "definition"); }
    }
    /**
     * 框架语义已转换为通用节点，可直接进入参数求值及渲染。
     */
    record Template(SqlNode node) implements SqlInput {
        public Template {
            Objects.requireNonNull(node, "node");
        }
    }

    /**
     * 保留值的使用位置，确保到达定义查询以该语句之前的控制流状态为准。
     */
    record ValueAt(JavaSootMethod method, Stmt statement, Value value) implements SqlInput {
        public ValueAt {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(statement, "statement");
            Objects.requireNonNull(value, "value");
        }
    }
}
