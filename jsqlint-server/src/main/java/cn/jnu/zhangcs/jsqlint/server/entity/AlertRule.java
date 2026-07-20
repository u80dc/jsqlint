package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警规则实体
 *
 * @author zhangcs
 */
@Data
@TableName("jsl_alert_rule")
public class AlertRule {

    /**
     * 告警规则ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 规则名称
     */
    private String name;

    /**
     * 规则类型
     */
    private String ruleType;

    /**
     * 适用项目ID，为空表示全部项目
     */
    private Long projectId;

    /**
     * 告警级别
     */
    private String severity;

    /**
     * 审核问题最低级别
     */
    private String minSeverity;

    /**
     * 触发阈值
     */
    private Integer thresholdCount;

    /**
     * 是否通知项目成员: 0-否, 1-是
     */
    private Integer notifyProjectMembers;

    /**
     * 是否通知项目创建者: 0-否, 1-是
     */
    private Integer notifyCreator;

    /**
     * 是否启用: 0-禁用, 1-启用
     */
    private Integer enabled;

    /**
     * 创建人ID
     */
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
     * 项目名称（非持久化，用于查询结果展示）
     */
    @TableField(exist = false)
    private String projectName;
}
