package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.model.vo.*;

/**
 * 项目概览服务接口
 * <p>提供单个项目维度的 SQL 概览、规则命中、问题 SQL、质量趋势、问题分类、整改率、问题占比与评分等看板数据。
 *
 * @author zhangcs
 */
public interface ProjectOverviewService {

    /**
     * SQL 概览：按 SQL 类型（SELECT/INSERT/UPDATE/DELETE）分布统计
     *
     * @param projectId 项目ID
     * @return SQL 概览视图
     */
    ProjectOverviewSqlVO getSqlOverview(Long projectId);

    /**
     * 规则概览：启用规则数量、按分类与级别统计
     *
     * @param projectId 项目ID
     * @return 规则概览视图
     */
    ProjectOverviewRuleVO getRuleOverview(Long projectId);

    /**
     * 规则命中排行（命中次数 Top N）
     *
     * @param projectId 项目ID
     * @param limit     返回条数（可选）
     * @return 规则命中视图
     */
    ProjectOverviewRuleHitVO getRuleHitOverview(Long projectId, Integer limit);

    /**
     * 问题 SQL 概览：问题 SQL 数量与占比
     *
     * @param projectId 项目ID
     * @return 问题 SQL 视图
     */
    ProjectOverviewIssueSqlVO getIssueSqlOverview(Long projectId);

    /**
     * SQL 质量趋势：按版本维度展示问题 SQL 变化
     *
     * @param projectId 项目ID
     * @return 质量趋势视图
     */
    ProjectOverviewSqlQualityTrendVO getSqlQualityTrend(Long projectId);

    /**
     * 问题分类概览：按安全/性能/质量等分类统计问题 SQL
     *
     * @param projectId 项目ID
     * @return 问题分类视图
     */
    ProjectOverviewIssueCategoryVO getIssueCategoryOverview(Long projectId);

    /**
     * 问题整改率：已处理问题占比
     *
     * @param projectId 项目ID
     * @return 整改率视图
     */
    ProjectOverviewIssueRectificationVO getIssueRectificationRate(Long projectId);

    /**
     * 问题 SQL 占比
     *
     * @param projectId 项目ID
     * @return 问题占比视图
     */
    ProjectOverviewIssueSqlRateVO getIssueSqlRate(Long projectId);

    /**
     * 项目质量评分与评级
     *
     * @param projectId 项目ID
     * @return 评分视图
     */
    ProjectOverviewScoreVO getProjectScore(Long projectId);
}
