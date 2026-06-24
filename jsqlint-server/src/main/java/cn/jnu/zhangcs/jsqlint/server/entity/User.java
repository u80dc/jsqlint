package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户表实体
 *
 * @author zhangcs
 */
@Getter
@Setter
@TableName("jsl_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String account;

    private String password;

    private String username;

    private String email;

    private String phone;

    private Integer status;

    private String role;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

}