package cn.jnu.zhangcs.jsqlint.server.entity;

import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStatus;
import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStepType;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 项目版本表实体，用于表示某个阶段项目的快照信息
 *
 * @author zhangcs
 */
@Getter
@Setter
@TableName("jsl_project_version")
public class ProjectVersion {

    /**
     * 版本ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 项目ID
     */
    private Long projectId;

    /**
     * 分支或标签引用
     */
    private String ref;

    /**
     * Git 提交ID
     */
    private String commitId;

    /**
     * 提交信息
     */
    private String commitMessage;

    /**
     * 提交作者
     */
    private String author;

    /**
     * 操作人ID
     */
    private Long operatorId;

    /**
     * 操作时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime operationTime;

    /**
     * 版本整体状态: 0-未开始, 1-执行中, 2-执行成功, 3-执行失败
     */
    private Integer overallStatus;

    /**
     * 当前执行步骤: 0-同步代码, 1-编译检查, 2-SQL扫描, 3-规则审核, 4-生成报告
     */
    private Integer currentStep;

    /**
     * 失败时的错误信息
     */
    private String message;

    /**
     * 操作人名称（非持久化，用于查询结果展示）
     */
    @TableField(exist = false)
    private String operatorName;

    /**
     * 项目名称（非持久化，用于查询结果展示）
     */
    @TableField(exist = false)
    private String projectName;

    /**
     * 以枚举形式设置版本整体状态
     *
     * @param status 版本整体状态枚举
     */
    public void setOverallStatus(ProjectVersionStatus status) {
        this.overallStatus = status != null ? status.getCode() : null;
    }

    /**
     * 以整型设置版本整体状态
     *
     * @param overallStatus 版本整体状态码
     */
    public void setOverallStatus(Integer overallStatus) {
        this.overallStatus = overallStatus;
    }

    /**
     * 以枚举形式设置当前步骤
     *
     * @param currentStep 步骤类型枚举
     */
    public void setCurrentStep(ProjectVersionStepType currentStep) {
        this.currentStep = currentStep != null ? currentStep.getCode() : null;
    }

    /**
     * 以整型设置当前步骤
     *
     * @param currentStep 步骤类型码
     */
    public void setCurrentStep(Integer currentStep) {
        this.currentStep = currentStep;
    }
}
