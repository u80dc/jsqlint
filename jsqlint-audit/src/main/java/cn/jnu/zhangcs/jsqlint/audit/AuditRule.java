package cn.jnu.zhangcs.jsqlint.audit;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * SQL审核规则定义。
 *
 * <p>该对象通常由服务端从数据库规则表读取后传入 {@link SqlAuditTool}。
 * 其中 {@link #droolsContent} 保存可被 Drools 编译执行的 DRL 规则片段，
 * 其余字段用于标识规则、展示规则元数据以及把命中的 Drools 规则映射回业务规则。</p>
 *
 * @author zhangcs
 */
@Data
public class AuditRule {

    /**
     * 数据库规则主键。
     */
    private Long id;

    /**
     * 规则展示名称。
     */
    private String name;

    /**
     * 规则编码，建议保持全局唯一，便于审计结果追踪。
     */
    private String code;

    /**
     * 规则说明。
     */
    private String description;

    /**
     * 业务风险等级，例如 high、warning 等。
     */
    private String severity;

    /**
     * 规则分类，例如 SQL规范、安全风险、性能风险等。
     */
    private String category;

    /**
     * Drools DRL 规则片段。
     *
     * <p>该字段只需要包含 rule/when/then/end 主体，{@link SqlAuditTool}
     * 会在编译前统一追加 package 和 import。</p>
     */
    private String droolsContent;

    /**
     * 修复建议。
     */
    private String suggestion;

    /**
     * 是否启用。值为 0 时 {@link SqlAuditTool} 会忽略该规则。
     */
    private Integer enabled;

    /**
     * 创建人用户ID。
     */
    private Long createUserId;

    /**
     * 创建时间。
     */
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    private LocalDateTime updateTime;

    /**
     * 创建人名称，通常为查询后填充的展示字段。
     */
    private String createUserName;

    /**
     * 风险等级描述，通常为查询后填充的展示字段。
     */
    private String severityDescription;

    /**
     * 规则分类描述，通常为查询后填充的展示字段。
     */
    private String categoryDescription;
}
