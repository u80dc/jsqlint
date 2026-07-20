package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审核规则实体
 *
 * @author zhangcs
 */
@Data
@TableName("jsl_audit_rule")
public class AuditRule {

    /**
     * 规则ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 规则名称
     */
    private String name;

    /**
     * 规则代码（唯一）
     */
    private String code;

    /**
     * 规则描述
     */
    private String description;

    /**
     * 严重级别: critical/high/warning/info
     */
    private String severity;

    /**
     * 规则分类: SQL_QUALITY/SECURITY/PERFORMANCE
     */
    private String category;

    /**
     * 项目ID，为空表示全局规则
     */
    private Long projectId;

    /**
     * Drools 规则内容（DRL）
     */
    @TableField("drools_content")
    private String droolsContent;

    /**
     * 修复建议
     */
    private String suggestion;

    /**
     * 是否启用: 0-禁用, 1-启用
     */
    private Integer enabled;

    /**
     * 创建人ID
     */
    @TableField(fill = FieldFill.INSERT)
    private Long createUserId;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 创建人名称（非持久化，用于查询结果展示）
     */
    @TableField(exist = false)
    private String createUserName;

    /**
     * 严重级别描述（非持久化）
     */
    @TableField(exist = false)
    private String severityDescription;

    /**
     * 规则分类描述（非持久化）
     */
    @TableField(exist = false)
    private String categoryDescription;
}
