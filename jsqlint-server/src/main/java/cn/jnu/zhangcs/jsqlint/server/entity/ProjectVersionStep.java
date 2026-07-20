package cn.jnu.zhangcs.jsqlint.server.entity;

import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStepStatus;
import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStepType;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目版本执行步骤实体，记录版本各阶段的执行状态
 *
 * @author zhangcs
 */
@Data
@TableName("jsl_project_version_step")
public class ProjectVersionStep {

    /**
     * 步骤ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 项目ID
     */
    private Long projectId;

    /**
     * 版本ID
     */
    private Long versionId;

    /**
     * 步骤类型: 0-同步代码, 1-编译检查, 2-SQL扫描, 3-规则审核, 4-生成报告
     */
    private Integer stepType;

    /**
     * 步骤顺序
     */
    private Integer stepOrder;

    /**
     * 步骤状态: 0-未开始, 1-执行中, 2-执行成功, 3-执行失败
     */
    private Integer status;

    /**
     * 关联任务ID
     */
    private Long taskId;

    /**
     * 失败信息
     */
    private String errorMessage;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

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
     * 以枚举形式设置步骤类型，并同步设置步骤顺序
     *
     * @param stepType 步骤类型枚举
     */
    public void setStepType(ProjectVersionStepType stepType) {
        this.stepType = stepType == null ? null : stepType.getCode();
        this.stepOrder = stepType == null ? null : stepType.getStepOrder();
    }

    /**
     * 以整型设置步骤类型
     *
     * @param stepType 步骤类型码
     */
    public void setStepType(Integer stepType) {
        this.stepType = stepType;
    }

    /**
     * 以枚举形式设置步骤状态
     *
     * @param status 步骤状态枚举
     */
    public void setStatus(ProjectVersionStepStatus status) {
        this.status = status == null ? null : status.getCode();
    }

    /**
     * 以整型设置步骤状态
     *
     * @param status 步骤状态码
     */
    public void setStatus(Integer status) {
        this.status = status;
    }
}
