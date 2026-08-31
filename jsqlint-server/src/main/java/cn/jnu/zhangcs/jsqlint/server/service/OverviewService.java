package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewCurrentTaskVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewProjectScoreVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewProjectSummaryVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewRecentProjectVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewRuleHitVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewSeverityDistributionVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewTrendVO;

import java.util.List;

/**
 * 首页概览服务接口
 * <p>提供项目汇总、最近分析、分析趋势与当前任务等首页看板数据，统计范围限定为当前用户可见项目。
 *
 * @author zhangcs
 */
public interface OverviewService {

    /**
     * 项目汇总统计：项目总数与安全/警告/危险 SQL 数量（基于用户可见项目的最新版本）
     *
     * @param userId 当前用户ID
     * @return 汇总数据
     */
    OverviewProjectSummaryVO getProjectSummary(Long userId);

    /**
     * 最近分析项目列表（按操作时间倒序，最多 10 条）
     *
     * @param userId 当前用户ID
     * @return 最近分析记录列表
     */
    List<OverviewRecentProjectVO> listRecentProjects(Long userId);

    /**
     * 分析趋势
     *
     * @param userId 当前用户ID，用于过滤可见项目
     * @param period 统计周期: week/month/year
     * @return 返回安全、警告、危险三个类别在时间窗口内的趋势数据
     */
    OverviewTrendVO getAnalysisTrend(Long userId, String period);

    /**
     * 当前任务列表（待执行/执行中/等待重试，按下次执行时间升序）
     *
     * @param userId 当前用户ID
     * @return 当前任务列表
     */
    List<OverviewCurrentTaskVO> listCurrentTasks(Long userId);

    /**
     * 规则命中 TOP N 统计（跨用户可见项目，按命中次数降序）
     *
     * @param userId 当前用户ID，用于过滤可见项目
     * @param limit  返回条数上限，默认 10
     * @return 高频命中规则列表
     */
    OverviewRuleHitVO getRuleHitOverview(Long userId, Integer limit);

    /**
     * 问题严重级别分布统计（跨用户可见项目，critical/high/warning/info）
     *
     * @param userId 当前用户ID，用于过滤可见项目
     * @return 严重级别分布数据
     */
    OverviewSeverityDistributionVO getSeverityDistribution(Long userId);

    /**
     * 项目健康度排行（跨用户可见项目，按评分升序最差在前）
     *
     * @param userId 当前用户ID，用于过滤可见项目
     * @param limit  返回条数上限，默认 10
     * @return 项目评分列表
     */
    OverviewProjectScoreVO getProjectScoreRanking(Long userId, Integer limit);
}
