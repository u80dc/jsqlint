package cn.jnu.zhangcs.jsqlint.audit.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL审核事实对象，用于 Drools 规则引擎评估。
 *
 * <p>调用方提供 SQL 文本、SQL 类型和可选来源信息；Drools 规则根据这些输入字段判断风险，
 * 并通过 {@link #riskLevel}、{@link #riskFactors}、{@link #suggestion} 写回审核结果。</p>
 *
 * @author zhangcs
 */
@Getter
@Setter
public class SqlAuditFact {

    /**
     * 被审核的完整 SQL 文本。
     */
    private String sql;

    /**
     * SQL 类型，例如 SELECT、INSERT、UPDATE、DELETE。
     */
    private String sqlType;

    /**
     * SQL 来源方法，通常来自静态分析结果。
     */
    private String sourceMethod;

    /**
     * SQL 来源文件路径。
     */
    private String filePath;

    /**
     * SQL 来源行号。
     */
    private Integer lineNumber;

    /**
     * 规则执行后的风险等级，默认 INFO。
     */
    private RiskLevel riskLevel = RiskLevel.INFO;

    /**
     * 规则执行后累积的风险因子。
     */
    private List<String> riskFactors = new ArrayList<>();

    /**
     * 规则执行后给出的修复或治理建议。
     */
    private String suggestion;

    /**
     * 创建空事实对象，便于框架反射或调用方逐项填充字段。
     */
    public SqlAuditFact() {
    }

    /**
     * 创建包含 SQL 文本和 SQL 类型的事实对象。
     */
    public SqlAuditFact(String sql, String sqlType) {
        this.sql = sql;
        this.sqlType = sqlType;
    }

    /**
     * 追加一个风险因子。
     *
     * <p>Drools 规则通常在 then 块中调用该方法记录命中的风险点，
     * 例如 {@code $fact.addRiskFactor("DELETE_WITHOUT_WHERE")}。</p>
     */
    public void addRiskFactor(String factor) {
        this.riskFactors.add(factor);
    }

    /**
     * 返回用于日志调试的事实对象摘要。
     */
    @Override
    public String toString() {
        return "SqlAuditFact{" +
                "sql='" + sql + '\'' +
                ", sqlType='" + sqlType + '\'' +
                ", riskLevel=" + riskLevel +
                ", riskFactors=" + riskFactors +
                '}';
    }
}
