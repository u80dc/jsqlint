package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * SQL 变更日志 VO
 *
 * @author zhangcs
 */
@Data
@Builder
public class SqlChangeLogVO {

    private Long id;
    private Long projectId;
    private Long fromVersionId;
    private Long toVersionId;
    private String statementId;
    private String changeType;
    private String fromSqlHash;
    private String toSqlHash;
    private Map<String, Object> diffSummary;
    private LocalDateTime createTime;

    // 关联信息
    private String fromVersionName;
    private String toVersionName;
}
