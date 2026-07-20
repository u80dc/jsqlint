package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginVO {

    private Long id;

    private String token;

    private String username;

    private String realName;

    private String role;

    private String roleDescription;

    private Long expiresIn;
}