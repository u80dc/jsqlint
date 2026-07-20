package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审核规则版本实体，记录规则的历史版本
 *
 * @author zhangcs
 */
@Data
@TableName("jsl_audit_rule_version")
public class AuditRuleVersion {

    /**
     * 版本ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 规则ID
     */
    private Long ruleId;

    /**
     * 版本号
     */
    private Integer version;

    /**
     * 规则内容
     */
    private String droolsContent;

    /**
     * 修复建议
     */
    private String suggestion;

    /**
     * 变更说明
     */
    private String changeLog;

    /**
     * 操作人ID
     */
    private Long createUserId;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
