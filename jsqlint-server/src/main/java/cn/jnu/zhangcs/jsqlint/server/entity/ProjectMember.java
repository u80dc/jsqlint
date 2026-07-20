package cn.jnu.zhangcs.jsqlint.server.entity;

import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectMemberRole;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目成员实体，对应 jsl_project_member 表
 *
 * @author zhangcs
 */
@Data
@TableName("jsl_project_member")
public class ProjectMember {

    /**
     * 成员ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 项目ID
     */
    private Long projectId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 角色: 0-普通用户, 1-项目管理员
     */
    private Integer role;

    /**
     * 加入时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime joinTime;

    /**
     * 账号（非持久化，用于查询结果展示）
     */
    @TableField(exist = false)
    private String account;

    /**
     * 真实姓名（非持久化，用于查询结果展示）
     */
    @TableField(exist = false)
    private String username;

    /**
     * 获取成员角色枚举
     *
     * @return 成员角色枚举
     */
    public ProjectMemberRole getRoleEnum() {
        return ProjectMemberRole.from(role);
    }

    /**
     * 设置成员角色枚举
     *
     * @param role 成员角色枚举
     */
    public void setRoleEnum(ProjectMemberRole role) {
        this.role = role != null ? role.getCode() : null;
    }

    /**
     * 判断是否为项目管理员
     *
     * @return 若为项目管理员返回 true
     */
    public boolean isAdmin() {
        return ProjectMemberRole.ADMIN.getCode() == role;
    }
}