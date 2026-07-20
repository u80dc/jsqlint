package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体
 *
 * @author zhangcs
 */
@Data
@TableName("jsl_user")
public class User {

    /**
     * 用户ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 账号（登录名）
     */
    private String account;

    /**
     * 密码（BCrypt 加密存储）
     */
    private String password;

    /**
     * 真实姓名
     */
    private String username;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 手机号
     */
    private String phone;

    /**
     * 状态: 0-禁用, 1-启用, -1-已删除
     */
    private Integer status;

    /**
     * 系统角色（ROLE_USER、ROLE_ADMIN）
     */
    private String role;

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
     * 获取角色枚举
     *
     * @return 角色枚举
     */
    public Role getRoleEnum() {
        return role != null ? Role.from(role) : null;
    }

    /**
     * 设置角色枚举
     *
     * @param role 角色枚举
     */
    public void setRoleEnum(Role role) {
        this.role = role != null ? role.getValue() : null;
    }
}