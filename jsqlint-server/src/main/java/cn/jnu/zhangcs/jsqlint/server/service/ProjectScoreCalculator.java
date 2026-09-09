package cn.jnu.zhangcs.jsqlint.server.service;

/**
 * 项目质量评分统一计算器。
 *
 * <p>项目概览与审核报告此前各自实现评分（报告按问题条数 × 严重级别绝对扣分、
 * 概览按问题 SQL 数 × 规则分类归一化扣分），同一项目两处分值不一致。
 * 本类统一为概览的口径：按问题 SQL 数（scanId 去重）做规则分类加权，
 * 除以"总 SQL 数 × 最大权重"得到百分比扣分，大项目与小项目可比。</p>
 *
 * <p>评级门槛同步统一为：A ≥ 90 / B ≥ 75 / C ≥ 60 / 其余 D。
 * 历史报告是生成时点的快照，不回溯重算，仅影响之后新生成的报告。</p>
 *
 * @author zhangcs
 */
public final class ProjectScoreCalculator {

    public static final int SECURITY_SQL_WEIGHT = 4;
    public static final int PERFORMANCE_SQL_WEIGHT = 3;
    public static final int QUALITY_SQL_WEIGHT = 2;
    public static final int OTHER_SQL_WEIGHT = 1;

    private ProjectScoreCalculator() {
    }

    /**
     * 计算质量评分：100 − 加权问题 SQL 占比 × 100。
     *
     * @param totalSqlCount      总 SQL 数
     * @param securitySqlCount   命中 SECURITY 类规则的问题 SQL 数（scanId 去重）
     * @param performanceSqlCount 命中 PERFORMANCE 类规则的问题 SQL 数
     * @param qualitySqlCount    命中 SQL_QUALITY 类规则的问题 SQL 数
     * @param otherSqlCount      命中其余分类的问题 SQL 数
     * @return 0-100 的分数；无 SQL 时返回 100
     */
    public static int score(int totalSqlCount,
                            int securitySqlCount,
                            int performanceSqlCount,
                            int qualitySqlCount,
                            int otherSqlCount) {
        if (totalSqlCount <= 0) {
            return 100;
        }
        long weightedIssueScore = (long) securitySqlCount * SECURITY_SQL_WEIGHT
                + (long) performanceSqlCount * PERFORMANCE_SQL_WEIGHT
                + (long) qualitySqlCount * QUALITY_SQL_WEIGHT
                + (long) otherSqlCount * OTHER_SQL_WEIGHT;
        long maxWeightedScore = (long) totalSqlCount * SECURITY_SQL_WEIGHT;
        int deduction = (int) Math.round(weightedIssueScore * 100.0 / maxWeightedScore);
        return Math.max(0, 100 - deduction);
    }

    /**
     * 分数对应的评级：A ≥ 90 / B ≥ 75 / C ≥ 60 / 其余 D。
     */
    public static String rating(int score) {
        if (score >= 90) {
            return "A";
        }
        if (score >= 75) {
            return "B";
        }
        if (score >= 60) {
            return "C";
        }
        return "D";
    }
}
