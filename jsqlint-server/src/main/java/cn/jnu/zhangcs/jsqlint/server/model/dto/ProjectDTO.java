package cn.jnu.zhangcs.jsqlint.server.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProjectDTO {

    private Long id;

    @NotBlank(message = "项目名称不能为空")
    @Size(max = 100, message = "项目名称不能超过100个字符")
    private String name;

    @NotBlank(message = "Git地址不能为空")
    @Size(max = 500, message = "Git地址不能超过500个字符")
    private String gitUrl;

    @Size(max = 128, message = "描述不能超过128个字符")
    private String description;

    @Size(max = 100, message = "引用不能超过100个字符")
    private String ref;

    /** SQL 方言: mysql（默认）/postgresql/oracle */
    @Size(max = 20, message = "方言不能超过20个字符")
    private String dialect;

    @Size(max = 100, message = "提交ID不能超过100个字符")
    private String commitId;

    @Size(max = 500, message = "提交信息不能超过500个字符")
    private String commitMessage;

    @Size(max = 100, message = "作者不能超过100个字符")
    private String author;

    private Long credentialId;

    private Long workspaceId;

    private Integer enabled;
}