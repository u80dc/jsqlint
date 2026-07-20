package cn.jnu.zhangcs.jsqlint.server.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CredentialDTO {

    private Long id;

    @NotBlank(message = "凭证名称不能为空")
    @Size(max = 100, message = "凭证名称不能超过100个字符")
    private String name;

    private Integer visibility;

    private Integer status;

    @Size(max = 100, message = "账号不能超过100个字符")
    private String account;

    @Size(max = 500, message = "Token不能超过500个字符")
    private String token;

    @Size(max = 255, message = "密码不能超过255个字符")
    private String password;
}