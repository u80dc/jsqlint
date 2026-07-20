package cn.jnu.zhangcs.jsqlint.server.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserGroupUpdateDTO {

    @NotNull(message = "用户组ID不能为空")
    private Long id;

    @Size(max = 100, message = "用户组名称不能超过100个字符")
    private String name;

    @Size(max = 500, message = "用户组描述不能超过500个字符")
    private String description;

    private Integer status;
}