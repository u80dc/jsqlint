package cn.jnu.zhangcs.jsqlint.server.entity;

import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskStatus;
import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskType;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务实体，承载同步、编译、分析、审核、报告、巡检、告警、执行等任务
 *
 * @author zhangcs
 */
@Data
@TableName("jsl_scheduled_task")
public class ScheduledTask {

    /**
     * 任务ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 任务类型
     */
    private TaskType type;

    /**
     * 项目ID
     */
    private Long projectId;

    /**
     * 项目版本ID
     */
    private Long versionId;

    /**
     * 任务状态
     */
    private TaskStatus status;

    /**
     * CRON 表达式（周期任务）
     */
    private String cronExpr;

    /**
     * 下次执行时间（毫秒时间戳）
     */
    private Long nextExecTime;

    /**
     * 已重试次数
     */
    private Integer retryCount;

    /**
     * 最大重试次数
     */
    private Integer maxRetries;

    /**
     * 任务参数（JSON 格式）
     */
    private String payload;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 代码目录路径
     */
    private String codeDir;

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
}