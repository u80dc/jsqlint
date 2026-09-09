package cn.jnu.zhangcs.jsqlint.server.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProjectScoreCalculator} 单元测试：统一评分口径的锚点。
 *
 * @author zhangcs
 */
class ProjectScoreCalculatorTest {

    @Test
    void noSql_returns100() {
        assertThat(ProjectScoreCalculator.score(0, 3, 2, 1, 0)).isEqualTo(100);
    }

    @Test
    void cleanProject_returns100() {
        assertThat(ProjectScoreCalculator.score(50, 0, 0, 0, 0)).isEqualTo(100);
    }

    @Test
    void allSecurityIssues_returns0() {
        // 每条 SQL 都命中 SECURITY：加权占比 100%，扣满归零
        assertThat(ProjectScoreCalculator.score(10, 10, 0, 0, 0)).isEqualTo(0);
    }

    @Test
    void oneSecuritySqlAmong50_deducts2Percent() {
        // 1 条 SECURITY 问题 SQL / 50 条总量：4/(50×4)=2% → 98
        assertThat(ProjectScoreCalculator.score(50, 1, 0, 0, 0)).isEqualTo(98);
    }

    @Test
    void weightedCategories_aggregate() {
        // 2 SECURITY(8) + 1 PERF(3) + 1 QUALITY(2) + 1 OTHER(1) = 14；14/(10×4)=35% → 65
        assertThat(ProjectScoreCalculator.score(10, 2, 1, 1, 1)).isEqualTo(65);
    }

    @Test
    void issueCountDoesNotAffectScore_onlyDistinctSql() {
        // 条数口径的同分不适用于分类口径：1 SECURITY(4) → 98；1 SECURITY+1 PERF+1 QUALITY+1 OTHER(10) → 95
        assertThat(ProjectScoreCalculator.score(50, 1, 0, 0, 0)).isEqualTo(98);
        assertThat(ProjectScoreCalculator.score(50, 1, 1, 1, 1)).isEqualTo(95);
        // 4 条 OTHER(4×1=4) 与 1 条 SECURITY(4) 扣分相同——权重体系如此
        assertThat(ProjectScoreCalculator.score(50, 0, 0, 0, 4)).isEqualTo(98);
    }

    @Test
    void rating_thresholds() {
        assertThat(ProjectScoreCalculator.rating(100)).isEqualTo("A");
        assertThat(ProjectScoreCalculator.rating(90)).isEqualTo("A");
        assertThat(ProjectScoreCalculator.rating(89)).isEqualTo("B");
        assertThat(ProjectScoreCalculator.rating(75)).isEqualTo("B");
        assertThat(ProjectScoreCalculator.rating(74)).isEqualTo("C");
        assertThat(ProjectScoreCalculator.rating(60)).isEqualTo("C");
        assertThat(ProjectScoreCalculator.rating(59)).isEqualTo("D");
        assertThat(ProjectScoreCalculator.rating(0)).isEqualTo("D");
    }

    @Test
    void deductionClampedAtZero() {
        // 超额问题（计数异常）不会产生负分
        assertThat(ProjectScoreCalculator.score(1, 5, 5, 5, 5)).isEqualTo(0);
    }
}
