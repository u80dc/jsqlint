package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * SQL 参数传播路径。
 *
 * <p>基于污点分析（{@code CallTrace}/{@code ValueFlowStep}）结果，
 * 描述 SQL 某个参数从调用点向上游回溯得到的传播链路。
 * 用于在 SQL 构造详情页中展示参数的来源、跨方法调用关系与方法内值流。
 *
 * @author zhangcs
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlPropagationPathVO {

    /**
     * SQL 语句记录 ID。
     */
    private Long id;

    /**
     * 项目 ID。
     */
    private Long projectId;

    /**
     * 项目版本 ID。
     */
    private Long versionId;

    /**
     * Mapper Statement ID。
     */
    private String statementId;

    /**
     * 原始 SQL 文本。
     */
    private String rawSql;

    /**
     * 参数维度的传播路径列表。
     */
    private List<ParameterPropagation> parameters;

    /**
     * 诊断信息。
     */
    private List<String> diagnostics;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParameterPropagation {

        /**
         * 参数名称。
         */
        private String name;

        /**
         * 参数下标。
         */
        private Integer index;

        /**
         * 参数类型。
         */
        private String type;

        /**
         * 参数绑定到 SQL 的方式：{@code PARAMETER_BINDING} / {@code RAW_SUBSTITUTION} /
         * {@code SQL_TEXT_REFERENCE} / {@code UNKNOWN}。
         */
        private String bindingType;

        /**
         * 该参数对应的传播路径列表（可能值维度）。
         */
        private List<PropagationPath> paths;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PropagationPath {

        /**
         * 链路根值展示文本。
         */
        private String rootValue;

        /**
         * 调用链步骤。
         */
        private List<CallTraceStep> steps;

        /**
         * 方法内值传播步骤。
         */
        private List<ValueFlowStepVO> valueFlow;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CallTraceStep {

        /**
         * 调用者方法签名。
         */
        private String caller;

        /**
         * 被调用方法签名。
         */
        private String callee;

        /**
         * 调用点语句。
         */
        private String callSite;

        /**
         * 实参下标。
         */
        private Integer argumentIndex;

        /**
         * 实参解析结果。
         */
        private String argument;

        /**
         * 单步摘要。
         */
        private String summary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValueFlowStepVO {

        /**
         * 所在方法签名。
         */
        private String method;

        /**
         * 语句在方法体中的下标，无法定位时为 -1。
         */
        private Integer statementIndex;

        /**
         * Jimple 语句字符串。
         */
        private String statement;

        /**
         * 传播步骤类型。
         */
        private String kind;

        /**
         * 当前步骤关注的变量、数组元素或表达式。
         */
        private String subject;

        /**
         * 当前步骤解析出的结构化值。
         */
        private String value;

        /**
         * 单步摘要。
         */
        private String summary;
    }

    /**
     * 兼容直接返回原始 Map 结构的传播路径（例如旧版本数据）。
     */
    private List<Map<String, Object>> rawPaths;
}
