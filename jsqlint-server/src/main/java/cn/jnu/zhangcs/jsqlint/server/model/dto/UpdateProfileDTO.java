package cn.jnu.zhangcs.jsqlint.server.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新个人信息请求
 *
 * @author zhangcs
 */
@Data
public class UpdateProfileDTO {

    @Size(max = 64, message = "姓名长度不能超过64个字符")
    private String realName;

    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱长度不能超过128个字符")
    private String email;

    @Size(max = 20, message = "手机号长度不能超过20个字符")
    private String phone;
}
