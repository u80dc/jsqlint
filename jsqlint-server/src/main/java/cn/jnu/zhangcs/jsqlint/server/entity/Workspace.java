package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作空间实体
 *
 * @author zhangcs
 */
@Data
@TableName("jsl_workspace")
public class Workspace {

    /**
     * 工作空间ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 工作空间名称
     */
    private String name;

    /**
     * 创建者ID
     */
    private Long creatorId;

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
     * 创建者名称（非持久化，用于查询结果展示）
     */
    @TableField(exist = false)
    private String creatorName;

    /**
     * 项目数量（非持久化，用于查询结果展示）
     */
    @TableField(exist = false)
    private Integer projectCount;
}