package cn.jnu.zhangcs.jsqlint.audit.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * SQL审核风险级别。
 *
 * <p>Drools 规则可通过 {@code $fact.setRiskLevel(RiskLevel.HIGH)}
 * 写入风险等级，最终由 {@code SqlAuditResult} 返回给调用方。</p>
 *
 * @author zhangcs
 */
@Getter
@AllArgsConstructor
public enum RiskLevel {

    /**
     * 信息级，通常表示未发现明确风险。
     */
    INFO("info", "信息"),

    /**
     * 警告级，通常表示建议优化或存在较低风险。
     */
    WARNING("warning", "警告"),

    /**
     * 高危级，通常表示需要优先处理的 SQL 风险。
     */
    HIGH("high", "高危"),

    /**
     * 致命级，通常表示严重安全或数据破坏风险。
     */
    CRITICAL("critical", "致命");

    /**
     * 风险等级编码，用于持久化和接口传输。
     */
    private final String code;

    /**
     * 风险等级中文描述。
     */
    private final String description;

    /**
     * 根据编码解析风险等级；无法识别时返回 {@link #INFO}。
     */
    public static RiskLevel from(String code) {
        for (RiskLevel level : values()) {
            if (level.code.equals(code)) {
                return level;
            }
        }
        return INFO;
    }

}
