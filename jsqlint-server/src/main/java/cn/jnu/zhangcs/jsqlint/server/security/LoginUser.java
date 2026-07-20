package cn.jnu.zhangcs.jsqlint.server.security;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginUser {

    private String username;

    private Long userId;
}