package cn.jnu.zhangcs.jsqlint.server.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProjectVersionDTO {

    private Long id;

    private Long projectId;

    @NotBlank(message = "分支不能为空")
    @Size(max = 100, message = "引用不能超过100个字符")
    private String ref;

    @NotBlank(message = "提交ID不能为空")
    @Size(max = 100, message = "提交ID不能超过100个字符")
    private String commitId;

    @Size(max = 500, message = "提交信息不能超过500个字符")
    private String commitMessage;

    @Size(max = 100, message = "作者不能超过100个字符")
    private String author;
}