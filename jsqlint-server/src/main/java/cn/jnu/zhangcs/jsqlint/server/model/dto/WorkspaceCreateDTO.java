package cn.jnu.zhangcs.jsqlint.server.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建工作空间DTO
 *
 * @author zhangcs
 */
@Data
public class WorkspaceCreateDTO {

    @NotBlank(message = "工作空间名称不能为空")
    @Size(max = 100, message = "工作空间名称不能超过100个字符")
    private String name;
}