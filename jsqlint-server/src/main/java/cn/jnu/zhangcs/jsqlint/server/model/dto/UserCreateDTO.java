package cn.jnu.zhangcs.jsqlint.server.model.dto;

import lombok.Data;

@Data
public class UserCreateDTO {

    private String username;

    private String password;

    private String realName;

    private String email;

    private String phone;

    private Integer status;

    private String role;
}