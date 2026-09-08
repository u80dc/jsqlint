package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审核问题 VO（处理视角）
 *
 * @author zhangcs
 */
@Data
public class AuditIssueVO {

    private Long id;

    private Long projectId;

    private Long scanId;

    private Long ruleId;

    private String ruleName;

    private String severity;

    private String message;

    private String filePath;

    private Integer lineNumber;

    private String sqlContent;

    /**
     * SQL 摘要（前 200 字符，列表展示用）
     */
    private String sqlSummary;

    private String suggestion;

    private String status;

    private String statusDescription;

    private Long handleUserId;

    private String handleUserName;

    private LocalDateTime handleTime;

    private String handleRemark;

    private LocalDateTime createTime;
}
