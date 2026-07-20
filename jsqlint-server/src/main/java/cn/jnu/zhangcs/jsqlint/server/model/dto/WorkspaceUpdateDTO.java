package cn.jnu.zhangcs.jsqlint.server.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新工作空间DTO
 *
 * @author zhangcs
 */
@Data
public class WorkspaceUpdateDTO {

    @NotNull(message = "工作空间ID不能为空")
    private Long id;

    @Size(max = 100, message = "工作空间名称不能超过100个字符")
    private String name;
}