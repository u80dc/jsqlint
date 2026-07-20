package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户组实体
 *
 * @author zhangcs
 */
@Data
@TableName("jsl_user_group")
public class UserGroup {

    /**
     * 用户组ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户组名称
     */
    private String name;

    /**
     * 用户组描述
     */
    private String description;

    /**
     * 创建者ID
     */
    private Long creatorId;

    /**
     * 状态: 0-正常, -1-已删除
     */
    private Integer status;

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
     * 成员数量（非持久化，用于查询结果展示）
     */
    @TableField(exist = false)
    private Integer memberCount;
}