package cn.jnu.zhangcs.jsqlint.server.model.dto;

import lombok.Data;

@Data
public class UserUpdateDTO {

    private Long id;

    private String realName;

    private String email;

    private String phone;

    private Integer status;

    private String role;

    private String password;
}