package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * SQL 语句 VO
 *
 * @author zhangcs
 */
@Data
@Builder
public class SqlStatementVO {

    private Long id;
    private Long projectId;
    private Long versionId;
    private String statementId;
    private String statementType;
    private String sqlHash;
    private String rawSql;
    private String sourceType;
    private String sourceFile;
    private Integer sourceLine;
    private String mapperClass;
    private String mapperMethod;
    private Map<String, Object> sqlNodeStructure;
    private Map<String, Object> parameterInfo;
    private Map<String, Object> metadata;
    private Boolean isDynamic;
    private Integer paramCount;
    private LocalDateTime createTime;
}
