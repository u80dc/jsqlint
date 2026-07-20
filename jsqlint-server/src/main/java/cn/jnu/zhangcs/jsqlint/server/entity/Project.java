package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目实体
 *
 * @author zhangcs
 */
@Data
@TableName("jsl_project")
public class Project {

    /**
     * 项目ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 项目名称
     */
    private String name;

    /**
     * 项目描述
     */
    private String description;

    /**
     * Git 仓库地址
     */
    private String gitUrl;

    /**
     * 分支或标签
     */
    private String ref;

    /**
     * 状态: 0-禁用, 1-启用
     */
    private Integer status;

    /**
     * 创建用户ID
     */
    private Long createUserId;

    /**
     * 凭证ID
     */
    private Long credentialId;

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

}