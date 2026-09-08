package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审核结果实体
 *
 * @author zhangcs
 */
@Data
@TableName("jsl_audit_result")
public class AuditResult {

    /**
     * 结果ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 项目ID
     */
    private Long projectId;

    /**
     * 扫描记录ID（关联 jsl_sql_statement）
     */
    private Long scanId;

    /**
     * 触发规则ID
     */
    private Long ruleId;

    /**
     * 严重级别: critical/high/warning/info
     */
    private String severity;

    /**
     * 问题描述
     */
    private String message;

    /**
     * 文件路径
     */
    private String filePath;

    /**
     * 行号
     */
    private Integer lineNumber;

    /**
     * SQL 内容
     */
    private String sqlContent;

    /**
     * 修复建议
     */
    private String suggestion;

    /**
     * 处理状态: OPEN-待处理, CONFIRMED-已确认, RESOLVED-已修复, DISMISSED-已豁免
     */
    private String status;

    /**
     * 处理人ID
     */
    private Long handleUserId;

    /**
     * 处理时间
     */
    private LocalDateTime handleTime;

    /**
     * 处理备注（豁免原因/修复说明）
     */
    private String handleRemark;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 规则名称（非持久化，用于查询结果展示）
     */
    @TableField(exist = false)
    private String ruleName;

    /**
     * 项目名称（非持久化，用于查询结果展示）
     */
    @TableField(exist = false)
    private String projectName;

    /**
     * 处理人名称（非持久化，用于查询结果展示）
     */
    @TableField(exist = false)
    private String handleUserName;

    /**
     * 处理状态描述（非持久化，用于查询结果展示）
     */
    @TableField(exist = false)
    private String statusDescription;
}