package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户组表实体
 *
 * @author zhangcs
 */
@Getter
@Setter
@TableName("jsl_user_group")
public class UserGroup {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    /**
     * 创建者
     */
    private Long creatorId;

    /**
     * 状态：0 正常，-1 已删除
     */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableField(exist = false)
    private String creatorName;

    @TableField(exist = false)
    private Integer memberCount;

}