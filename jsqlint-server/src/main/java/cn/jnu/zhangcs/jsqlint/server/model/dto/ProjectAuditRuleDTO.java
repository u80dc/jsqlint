package cn.jnu.zhangcs.jsqlint.server.model.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 项目审核规则创建/更新DTO
 */
@Data
public class ProjectAuditRuleDTO {

    @Size(max = 100, message = "规则名称不能超过100字符")
    private String name;

    @Size(max = 50, message = "规则代码不能超过50字符")
    @Pattern(regexp = "^[A-Z_][A-Z0-9_]*$", message = "规则代码必须是大写字母、数字和下划线组成")
    private String code;

    @Size(max = 500, message = "规则描述不能超过500字符")
    private String description;

    @Pattern(regexp = "^(critical|high|warning|info)$", message = "严重级别必须是 critical/high/warning/info 之一")
    private String severity;

    @Pattern(regexp = "^(SQL_QUALITY|SECURITY|PERFORMANCE)$", message = "规则分类必须是 SQL_QUALITY/SECURITY/PERFORMANCE 之一")
    private String category;

    /**
     * SQL 方言: null/空白-通用，mysql/postgresql/oracle（规则仅对同方言 SQL 生效）
     */
    @Pattern(regexp = "^(mysql|postgresql|oracle)$", message = "SQL方言必须是 mysql/postgresql/oracle 之一")
    private String dialect;

    private String droolsContent;

    @Size(max = 500, message = "修复建议不能超过500字符")
    private String suggestion;

    private Boolean enabled;
}
