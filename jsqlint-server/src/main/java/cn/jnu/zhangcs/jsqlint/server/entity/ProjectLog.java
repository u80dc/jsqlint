package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目操作日志
 *
 * @author zhangcs
 */
@Data
@TableName("jsl_project_log")
public class ProjectLog {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 项目ID
     */
    private Long projectId;

    /**
     * 操作类型（PROJECT_CREATE/MEMBER_ADD/MEMBER_REMOVE/RULE_ENABLE 等）
     */
    private String operationType;

    /**
     * 操作用户ID
     */
    private Long operationUserId;

    /**
     * 操作用户名（冗余字段）
     */
    private String operationUserName;

    /**
     * 操作信息文本
     */
    private String operationMessage;

    /**
     * 关联的资源ID（成员ID、规则ID、凭证ID等）
     */
    private Long relatedId;

    /**
     * 操作时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime operationTime;

    /**
     * IP 地址
     */
    private String ipAddress;

    /**
     * 额外信息（JSON）
     */
    private String extraInfo;

    /**
     * 项目名称（非持久化，用于查询结果展示）
     */
    @TableField(exist = false)
    private String projectName;
}
