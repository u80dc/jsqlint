package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户与用户组关系表实体
 *
 * @author zhangcs
 */
@Getter
@Setter
@TableName("jsl_user_group_member")
public class UserGroupMember {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户
     */
    private Long userId;

    /**
     * 用户组
     */
    private Long groupId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime joinTime;

}