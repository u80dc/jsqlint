package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警实例实体，按指纹去重聚合
 *
 * @author zhangcs
 */
@Data
@TableName("jsl_alert_instance")
public class AlertInstance {

    /**
     * 告警实例ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 告警规则ID
     */
    private Long ruleId;

    /**
     * 项目ID
     */
    private Long projectId;

    /**
     * 项目版本ID
     */
    private Long versionId;

    /**
     * 触发任务ID
     */
    private Long taskId;

    /**
     * 标题
     */
    private String title;

    /**
     * 内容
     */
    private String content;

    /**
     * 告警级别
     */
    private String severity;

    /**
     * 状态（OPEN/ACKED/RESOLVED/IGNORED）
     */
    private String status;

    /**
     * 去重指纹
     */
    private String fingerprint;

    /**
     * 触发次数
     */
    private Integer triggerCount;

    /**
     * 处理人ID
     */
    private Long assigneeId;

    /**
     * 首次触发时间
     */
    private LocalDateTime firstTriggeredAt;

    /**
     * 最近触发时间
     */
    private LocalDateTime lastTriggeredAt;

    /**
     * 确认时间
     */
    private LocalDateTime ackTime;

    /**
     * 解决时间
     */
    private LocalDateTime resolvedTime;

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
     * 规则名称（非持久化，用于查询结果展示）
     */
    @TableField(exist = false)
    private String ruleName;

    /**
     * 项目名称（非持久化，用于查询结果展示）
     */
    @TableField(exist = false)
    private String projectName;
}
