package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目巡检配置实体
 *
 * @author zhangcs
 */
@Data
@TableName("jsl_inspection_config")
public class InspectionConfig {

    /**
     * 巡检配置ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 项目ID
     */
    private Long projectId;

    /**
     * cron 表达式
     */
    private String cronExpr;

    /**
     * 是否启用: 0-禁用, 1-启用
     */
    private Integer enabled;

    /**
     * 下次执行时间（毫秒时间戳）
     */
    private Long nextExecTime;

    /**
     * 最近执行时间（毫秒时间戳）
     */
    private Long lastExecTime;

    /**
     * 最近执行状态
     */
    private String lastStatus;

    /**
     * 最近执行信息
     */
    private String lastMessage;

    /**
     * 操作人ID
     */
    private Long operatorId;

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
