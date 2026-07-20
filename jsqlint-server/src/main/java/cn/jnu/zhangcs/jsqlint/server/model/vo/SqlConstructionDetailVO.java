package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * SQL 构造过程详情。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlConstructionDetailVO {

    private Long id;

    private Long projectId;

    private Long versionId;

    private SqlDefinitionVO definition;

    private List<ConstructionStepVO> constructionSteps;

    private List<SqlParameterVO> parameters;

    private List<RenderedSqlVO> renderedSqls;

    private List<AuditResultVO> auditResults;

    private List<String> diagnostics;

    private Map<String, Object> sqlNodeStructure;

    private Map<String, Object> metadata;

    private LocalDateTime createTime;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SqlDefinitionVO {

        private String statementId;

        private String statementType;

        private String sqlHash;

        private String rawSql;

        private String sourceType;

        private String constructionType;

        private String sourceFile;

        private Integer sourceLine;

        private String mapperClass;

        private String mapperMethod;

        private Boolean dynamic;

        private Integer parameterCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConstructionStepVO {

        private String id;

        private Integer order;

        private Integer depth;

        private String nodeType;

        private String title;

        private String contribution;

        private String condition;

        private Boolean dynamic;

        private Map<String, Object> attributes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SqlParameterVO {

        private String name;

        private Integer index;

        private String type;

        private String target;

        private String bindingType;

        private String source;

        private List<Map<String, Object>> possibleValues;

        private List<Map<String, Object>> propagationPaths;

        private List<String> diagnostics;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RenderedSqlVO {

        private Integer index;

        private String sql;

        private Map<String, Object> parameters;

        private List<String> diagnostics;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditResultVO {

        private Long id;

        private Long ruleId;

        private String ruleName;

        private String severity;

        private String message;

        private String sqlContent;

        private String suggestion;

        private String filePath;

        private Integer lineNumber;
    }
}
