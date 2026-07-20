package cn.jnu.zhangcs.jsqlint.server.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * SQL 白名单创建 DTO
 */
@Data
public class SqlWhitelistDTO {

    /**
     * 原始 SQL（后端自动计算指纹）
     */
    @NotBlank(message = "SQL 语句不能为空")
    private String sql;

    /**
     * 审核规则 ID
     */
    @NotNull(message = "规则ID不能为空")
    private Long ruleId;

    /**
     * 加白原因
     */
    @Size(max = 500, message = "加白原因不能超过500字符")
    private String reason;
}
