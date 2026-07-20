package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户与用户组关系实体
 *
 * @author zhangcs
 */
@Data
@TableName("jsl_user_group_member")
public class UserGroupMember {

    /**
     * 关系ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户组ID
     */
    private Long userGroupId;

    /**
     * 加入时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime joinTime;

}