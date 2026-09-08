package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审核规则VO
 */
@Data
public class AuditRuleVO {

    private Long id;
    private String name;
    private String code;
    private String description;
    private String severity;
    private String severityDescription;
    private String category;
    private String categoryDescription;
    private Long projectId;
    private String droolsContent;
    private String suggestion;
    private Integer enabled;
    /**
     * 是否内置规则: 0-用户规则, 1-内置规则
     */
    private Integer isBuiltin;
    private String createUserName;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
