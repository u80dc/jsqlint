package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Git 凭证实体
 *
 * @author zhangcs
 */
@Data
@TableName("jsl_credential")
public class Credential {

    /**
     * 凭证ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 凭证名称
     */
    private String name;

    /**
     * 可见性: 0-公开, 1-团队, 2-私人
     */
    private Integer visibility;

    /**
     * 状态: 0-禁用, 1-启用, -1-已删除
     */
    private Integer status;

    /**
     * 账号
     */
    private String account;

    /**
     * 个人访问令牌
     */
    private String token;

    /**
     * 密码
     */
    private String password;

    /**
     * 创建用户ID
     */
    private Long createUserId;

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