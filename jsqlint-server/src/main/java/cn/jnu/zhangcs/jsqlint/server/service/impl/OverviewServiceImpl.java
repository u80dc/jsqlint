package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStatus;
import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskStatus;
import cn.jnu.zhangcs.jsqlint.server.entity.*;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditResultMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditRuleMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.ProjectVersionMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.ScheduledTaskMapper;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewCurrentTaskVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewProjectScoreVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewProjectSummaryVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewRecentProjectVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewRuleHitVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewSeverityDistributionVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewTrendVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectOverviewScoreVO;
import cn.jnu.zhangcs.jsqlint.server.service.OverviewService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectOverviewService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectVersionService;
import cn.jnu.zhangcs.jsqlint.server.service.SqlStatementService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OverviewServiceImpl implements OverviewService {

    private static final int RECENT_PROJECT_LIMIT = 10;

    private final ProjectService projectService;

    private final ProjectVersionMapper projectVersionMapper;

    private final ScheduledTaskMapper scheduledTaskMapper;

    private final ProjectVersionService projectVersionService;

    private final SqlStatementService sqlStatementService;

    private final AuditResultMapper auditResultMapper;

    private final AuditRuleMapper auditRuleMapper;

    private final ProjectOverviewService projectOverviewService;

    @Override
    public OverviewProjectSummaryVO getProjectSummary(Long userId) {
        List<Project> projects = visibleProjects(userId);
        int total = projects.size();
        if (total == 0) {
            return emptySummary();
        }

        Set<Long> projectIds = projects.stream()
                .map(Project::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        int totalSqlCount = 0;
        int dangerSqlCount = 0;
        int warningSqlCount = 0;

        // 统计各项目最新版本的 SQL 数量与按严重级别归类的问题 SQL 数
        for (Long projectId : projectIds) {
            ProjectVersion version = projectVersionService.getLast(projectId);
            if (version == null) {
                continue;
            }
            List<SqlStatement> statements = sqlStatementService.getByProjectAndVersion(projectId, version.getId());
            if (statements.isEmpty()) {
                continue;
            }
            totalSqlCount += statements.size();

            List<AuditResult> auditResults = auditResults(projectId, statements);
            if (auditResults.isEmpty()) {
                continue;
            }

            // 按 scanId 去重统计问题 SQL 数量，避免同一 SQL 命中多条规则导致重复计数
            dangerSqlCount += distinctSeveritySqlCount(auditResults, "critical", "high");
            warningSqlCount += distinctSeveritySqlCount(auditResults, "warning", "info");
        }

        int safeSqlCount = Math.max(0, totalSqlCount - dangerSqlCount - warningSqlCount);

        return OverviewProjectSummaryVO.builder()
                .totalProjects(total)
                .safeSqlCount(safeSqlCount)
                .warningSqlCount(warningSqlCount)
                .dangerSqlCount(dangerSqlCount)
                .build();
    }

    /**
     * 查询某项目版本下的全部审核结果
     */
    private List<AuditResult> auditResults(Long projectId, List<SqlStatement> statements) {
        List<Long> scanIds = statements.stream()
                .map(SqlStatement::getId)
                .filter(Objects::nonNull)
                .toList();
        if (scanIds.isEmpty()) {
            return List.of();
        }
        return auditResultMapper.selectList(Wrappers.<AuditResult>lambdaQuery()
                .eq(AuditResult::getProjectId, projectId)
                .in(AuditResult::getScanId, scanIds));
    }

    /**
     * 统计命中指定严重级别的"问题 SQL"数量（按 scanId 去重）
     *
     * @param auditResults 审核结果
     * @param severities   纳入统计的严重级别（不区分大小写）
     * @return 去重后的问题 SQL 数量
     */
    private int distinctSeveritySqlCount(List<AuditResult> auditResults, String... severities) {
        Set<String> targets = Set.of(severities);
        return (int) auditResults.stream()
                .filter(result -> result.getSeverity() != null && targets.contains(result.getSeverity().toLowerCase()))
                .map(AuditResult::getScanId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
    }

    /**
     * 返回全空的汇总结果
     */
    private OverviewProjectSummaryVO emptySummary() {
        return OverviewProjectSummaryVO.builder()
                .totalProjects(0)
                .safeSqlCount(0)
                .warningSqlCount(0)
                .dangerSqlCount(0)
                .build();
    }

    @Override
    public List<OverviewRecentProjectVO> listRecentProjects(Long userId) {
        List<Project> projects = visibleProjects(userId);
        if (projects.isEmpty()) {
            return List.of();
        }
        Map<Long, String> projectNames = projectNameIndex(projects);
        Set<Long> projectIds = projectNames.keySet();
        return projectVersionMapper.selectList(Wrappers.<ProjectVersion>lambdaQuery()
                        .in(ProjectVersion::getProjectId, projectIds)
                        .orderByDesc(ProjectVersion::getOperationTime)
                        .orderByDesc(ProjectVersion::getId)
                        .last("LIMIT " + RECENT_PROJECT_LIMIT))
                .stream()
                .map(version -> toRecentProjectVO(version, projectNames))
                .toList();
    }

    @Override
    public OverviewTrendVO getAnalysisTrend(Long userId, String period) {
        String normalizedPeriod = normalizePeriod(period);
        List<Project> projects = visibleProjects(userId);
        if (projects.isEmpty()) {
            return emptyTrend(normalizedPeriod);
        }

        Set<Long> projectIds = projects.stream()
                .map(Project::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 计算时间窗口与每个 bucket 的起止时间
        TimeWindow window = buildTimeWindow(normalizedPeriod);

        // 拉取窗口内相关项目的审核结果，用于统计问题 SQL
        List<AuditResult> auditResults = auditResultMapper.selectList(Wrappers.<AuditResult>lambdaQuery()
                .in(AuditResult::getProjectId, projectIds));

        Set<String> dangerLevels = Set.of("critical", "high");
        Set<String> warningLevels = Set.of("warning", "info");

        // 初始化各 bucket 的统计容器
        Map<String, BucketStat> statByLabel = new LinkedHashMap<>();
        for (Bucket bucket : window.buckets) {
            statByLabel.put(bucket.label, new BucketStat());
        }

        // SQL 总数归桶：以 SQL 记录的 create_time（即分析落库时间）作为归桶依据
        List<SqlStatement> statements = collectStatementsInWindow(projectIds, window.start, window.end);
        for (SqlStatement statement : statements) {
            Bucket bucket = window.bucketOf(statement.getCreateTime());
            if (bucket != null) {
                statByLabel.get(bucket.label).totalSql++;
            }
        }

        // 问题 SQL 归桶（按审核结果 create_time），按 scanId 去重后归类到 danger / warning
        for (AuditResult result : auditResults) {
            Bucket bucket = window.bucketOf(result.getCreateTime());
            if (bucket == null) {
                continue;
            }
            Long scanId = result.getScanId();
            String severity = result.getSeverity() == null ? null : result.getSeverity().toLowerCase();
            BucketStat stat = statByLabel.get(bucket.label);
            if (dangerLevels.contains(severity)) {
                if (scanId != null && stat.dangerScanIds.add(scanId)) {
                    stat.dangerSql++;
                }
            } else if (warningLevels.contains(severity)) {
                if (scanId != null && stat.warningScanIds.add(scanId)) {
                    stat.warningSql++;
                }
            }
        }

        // 组装返回：safe = total - danger - warning
        List<String> labels = new ArrayList<>(window.buckets.size());
        List<Integer> safe = new ArrayList<>(window.buckets.size());
        List<Integer> warning = new ArrayList<>(window.buckets.size());
        List<Integer> danger = new ArrayList<>(window.buckets.size());
        for (Bucket bucket : window.buckets) {
            BucketStat stat = statByLabel.get(bucket.label);
            int dangerCount = stat.dangerSql;
            int warningCount = stat.warningSql;
            int safeCount = Math.max(0, stat.totalSql - dangerCount - warningCount);
            labels.add(bucket.label);
            safe.add(safeCount);
            warning.add(warningCount);
            danger.add(dangerCount);
        }

        return OverviewTrendVO.builder()
                .period(normalizedPeriod)
                .labels(labels)
                .safe(safe)
                .warning(warning)
                .danger(danger)
                .build();
    }

    /**
     * 收集时间窗口内所有可见项目的 SQL 语句记录（用于趋势中的 SQL 总数统计）。
     * <p>以 SQL 记录的 create_time（即分析落库时间）作为归桶依据。
     */
    private List<SqlStatement> collectStatementsInWindow(Set<Long> projectIds, LocalDateTime start, LocalDateTime end) {
        List<SqlStatement> all = new ArrayList<>();
        for (Long projectId : projectIds) {
            ProjectVersion version = projectVersionService.getLast(projectId);
            if (version == null) {
                continue;
            }
            all.addAll(sqlStatementService.getByProjectAndVersion(projectId, version.getId()));
        }
        final LocalDateTime windowStart = start;
        final LocalDateTime windowEnd = end;
        return all.stream()
                .filter(statement -> statement.getCreateTime() != null)
                .filter(statement -> !statement.getCreateTime().isBefore(windowStart) && statement.getCreateTime().isBefore(windowEnd))
                .toList();
    }

    /**
     * 构造时间窗口：返回 bucket 列表与窗口起止时间。
     * <ul>
     *   <li>week：本周一到本周日，按天分 7 桶</li>
     *   <li>month：本月按周分桶（1-7、8-14、15-21、22-月末）</li>
     *   <li>year：本年 12 个月</li>
     * </ul>
     */
    private TimeWindow buildTimeWindow(String period) {
        LocalDate today = LocalDate.now();
        List<Bucket> buckets;
        LocalDateTime start;
        LocalDateTime end;
        switch (period) {
            case "month" -> {
                YearMonth currentMonth = YearMonth.from(today);
                LocalDate firstOfMonth = currentMonth.atDay(1);
                start = firstOfMonth.atStartOfDay();
                end = currentMonth.plusMonths(1).atDay(1).atStartOfDay();
                buckets = monthBuckets(currentMonth);
            }
            case "year" -> {
                int year = today.getYear();
                start = LocalDate.of(year, 1, 1).atStartOfDay();
                end = LocalDate.of(year + 1, 1, 1).atStartOfDay();
                buckets = yearBuckets(year);
            }
            default -> {
                LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                start = monday.atStartOfDay();
                end = monday.plusDays(7).atStartOfDay();
                buckets = weekBuckets(monday);
            }
        }
        return new TimeWindow(start, end, buckets);
    }

    /**
     * 本周按天生成 7 个桶（周一到周日）
     */
    private List<Bucket> weekBuckets(LocalDate monday) {
        String[] labels = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        List<Bucket> buckets = new ArrayList<>(7);
        for (int i = 0; i < 7; i++) {
            LocalDate day = monday.plusDays(i);
            buckets.add(new Bucket(labels[i], day.atStartOfDay(), day.plusDays(1).atStartOfDay()));
        }
        return buckets;
    }

    /**
     * 本月按周分桶：1-7、8-14、15-21、22-月末
     */
    private List<Bucket> monthBuckets(YearMonth month) {
        int lastDay = month.lengthOfMonth();
        int[][] ranges = {{1, 7}, {8, 14}, {15, 21}, {22, lastDay}};
        List<Bucket> buckets = new ArrayList<>(4);
        for (int[] range : ranges) {
            int from = range[0];
            int to = range[1];
            LocalDate startDay = month.atDay(from);
            LocalDate endDay = to >= lastDay ? month.plusMonths(1).atDay(1) : month.atDay(to + 1);
            String label = String.format("第%d周", ranges.length > 0 ? (startDay.getDayOfMonth() - 1) / 7 + 1 : 1);
            buckets.add(new Bucket(label, startDay.atStartOfDay(), endDay.atStartOfDay()));
        }
        // 用直观的周序标签
        List<String> labels = Arrays.asList("第1周", "第2周", "第3周", "第4周");
        List<Bucket> relabeled = new ArrayList<>(buckets.size());
        for (int i = 0; i < buckets.size(); i++) {
            Bucket original = buckets.get(i);
            relabeled.add(new Bucket(labels.get(i), original.start, original.end));
        }
        return relabeled;
    }

    /**
     * 本年按月生成 12 个桶
     */
    private List<Bucket> yearBuckets(int year) {
        List<Bucket> buckets = new ArrayList<>(12);
        for (int m = 1; m <= 12; m++) {
            YearMonth month = YearMonth.of(year, m);
            String label = m + "月";
            buckets.add(new Bucket(label, month.atDay(1).atStartOfDay(), month.plusMonths(1).atDay(1).atStartOfDay()));
        }
        return buckets;
    }

    /**
     * 返回全空的趋势结果（按周期填充标签）
     */
    private OverviewTrendVO emptyTrend(String period) {
        List<String> labels = switch (period) {
            case "month" -> List.of("第1周", "第2周", "第3周", "第4周");
            case "year" -> List.of("1月", "2月", "3月", "4月", "5月", "6月", "7月", "8月", "9月", "10月", "11月", "12月");
            default -> List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日");
        };
        int size = labels.size();
        return OverviewTrendVO.builder()
                .period(period)
                .labels(labels)
                .safe(zeros(size))
                .warning(zeros(size))
                .danger(zeros(size))
                .build();
    }

    private List<Integer> zeros(int size) {
        return java.util.stream.IntStream.range(0, size).map(i -> 0).boxed().toList();
    }

    /**
     * 时间窗口：起止时间 + 桶列表
     */
    private record TimeWindow(LocalDateTime start, LocalDateTime end, List<Bucket> buckets) {
        Bucket bucketOf(LocalDateTime time) {
            if (time == null) {
                return null;
            }
            for (Bucket bucket : buckets) {
                if (!time.isBefore(bucket.start) && time.isBefore(bucket.end)) {
                    return bucket;
                }
            }
            return null;
        }
    }

    /**
     * 桶：标签 + 半开区间 [start, end)
     */
    private record Bucket(String label, LocalDateTime start, LocalDateTime end) {
    }

    /**
     * 桶统计：SQL 总数、危险 SQL 数、警告 SQL 数，以及用于去重的 scanId 集合
     */
    private static final class BucketStat {
        private int totalSql;
        private int dangerSql;
        private int warningSql;
        private final Set<Long> dangerScanIds = new java.util.HashSet<>();
        private final Set<Long> warningScanIds = new java.util.HashSet<>();
    }

    @Override
    public List<OverviewCurrentTaskVO> listCurrentTasks(Long userId) {
        List<Project> projects = visibleProjects(userId);
        if (projects.isEmpty()) {
            return List.of();
        }
        Map<Long, String> projectNames = projectNameIndex(projects);
        List<ScheduledTask> tasks = scheduledTaskMapper.selectList(Wrappers.<ScheduledTask>lambdaQuery()
                .in(ScheduledTask::getProjectId, projectNames.keySet())
                .in(ScheduledTask::getStatus, TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.RETRYING)
                .orderByAsc(ScheduledTask::getNextExecTime)
                .orderByDesc(ScheduledTask::getUpdateTime));
        return tasks.stream()
                .sorted(Comparator.comparing(ScheduledTask::getNextExecTime, Comparator.nullsLast(Long::compareTo)))
                .map(task -> toCurrentTaskVO(task, projectNames))
                .toList();
    }

    private List<Project> visibleProjects(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return projectService.getByUser(userId);
    }

    private Map<Long, String> projectNameIndex(List<Project> projects) {
        return projects.stream()
                .filter(project -> project.getId() != null)
                .collect(Collectors.toMap(Project::getId, Project::getName, (left, right) -> left));
    }

    private OverviewRecentProjectVO toRecentProjectVO(ProjectVersion version, Map<Long, String> projectNames) {
        ProjectVersionStatus status = ProjectVersionStatus.from(version.getOverallStatus());
        return OverviewRecentProjectVO.builder()
                .projectId(version.getProjectId())
                .projectName(projectNames.get(version.getProjectId()))
                .versionId(version.getId())
                .ref(version.getRef())
                .commitId(version.getCommitId())
                .commitMessage(version.getCommitMessage())
                .status(version.getOverallStatus())
                .statusDescription(status.getDesc())
                .analysisTime(version.getOperationTime())
                .build();
    }

    private OverviewCurrentTaskVO toCurrentTaskVO(ScheduledTask task, Map<Long, String> projectNames) {
        return OverviewCurrentTaskVO.builder()
                .id(task.getId())
                .projectId(task.getProjectId())
                .projectName(projectNames.get(task.getProjectId()))
                .versionId(task.getVersionId())
                .type(task.getType() == null ? null : task.getType().getCode())
                .typeDescription(task.getType() == null ? null : task.getType().getDesc())
                .status(task.getStatus() == null ? null : task.getStatus().getCode())
                .statusDescription(task.getStatus() == null ? null : task.getStatus().getDesc())
                .nextExecTime(task.getNextExecTime())
                .retryCount(task.getRetryCount())
                .maxRetries(task.getMaxRetries())
                .errorMessage(task.getErrorMessage())
                .createTime(task.getCreateTime())
                .updateTime(task.getUpdateTime())
                .build();
    }

    private String normalizePeriod(String period) {
        if ("month".equalsIgnoreCase(period)) {
            return "month";
        }
        if ("year".equalsIgnoreCase(period)) {
            return "year";
        }
        return "week";
    }

    @Override
    public OverviewRuleHitVO getRuleHitOverview(Long userId, Integer limit) {
        List<AuditResult> auditResults = latestVersionAuditResults(userId);
        int topLimit = normalizeLimit(limit);

        // 按规则分组计数，null 规则ID归为 0 后在 VO 转换时还原为 null
        Map<Long, Integer> hitCountByRule = auditResults.stream()
                .collect(Collectors.groupingBy(
                        result -> result.getRuleId() == null ? 0L : result.getRuleId(),
                        Collectors.summingInt(result -> 1)));

        Map<Long, AuditRule> ruleIndex = ruleIndex(hitCountByRule.keySet());

        List<OverviewRuleHitVO.RuleHitCountVO> topRules = hitCountByRule.entrySet()
                .stream()
                .map(entry -> toRuleHitCountVO(entry.getKey(), entry.getValue(), ruleIndex))
                .sorted(Comparator
                        .comparing(OverviewRuleHitVO.RuleHitCountVO::getHitCount, Comparator.reverseOrder())
                        .thenComparing(rule -> rule.getRuleName() == null ? "" : rule.getRuleName()))
                .limit(topLimit)
                .toList();

        return OverviewRuleHitVO.builder()
                .totalHitCount(auditResults.size())
                .topRules(topRules)
                .build();
    }

    @Override
    public OverviewSeverityDistributionVO getSeverityDistribution(Long userId) {
        List<AuditResult> auditResults = latestVersionAuditResults(userId);

        Map<String, Long> countBySeverity = auditResults.stream()
                .collect(Collectors.groupingBy(
                        result -> result.getSeverity() == null ? "unknown" : result.getSeverity().toLowerCase(),
                        Collectors.counting()));

        return OverviewSeverityDistributionVO.builder()
                .totalIssueCount(auditResults.size())
                .criticalCount(countBySeverity.getOrDefault("critical", 0L).intValue())
                .highCount(countBySeverity.getOrDefault("high", 0L).intValue())
                .warningCount(countBySeverity.getOrDefault("warning", 0L).intValue())
                .infoCount(countBySeverity.getOrDefault("info", 0L).intValue())
                .build();
    }

    @Override
    public OverviewProjectScoreVO getProjectScoreRanking(Long userId, Integer limit) {
        List<Project> projects = visibleProjects(userId);
        if (projects.isEmpty()) {
            return OverviewProjectScoreVO.builder().projects(List.of()).build();
        }
        int topLimit = normalizeLimit(limit);
        Map<Long, String> projectNames = projectNameIndex(projects);

        List<OverviewProjectScoreVO.ProjectScoreVO> scores = projects.stream()
                .map(project -> toProjectScoreVO(project, projectNames))
                .sorted(Comparator.comparing(OverviewProjectScoreVO.ProjectScoreVO::getScore))
                .limit(topLimit)
                .toList();

        return OverviewProjectScoreVO.builder()
                .projects(scores)
                .build();
    }

    /**
     * 汇总用户可见项目各自最新版本下的全部审核结果
     */
    private List<AuditResult> latestVersionAuditResults(Long userId) {
        List<Project> projects = visibleProjects(userId);
        if (projects.isEmpty()) {
            return List.of();
        }
        List<AuditResult> all = new ArrayList<>();
        for (Project project : projects) {
            Long projectId = project.getId();
            if (projectId == null) {
                continue;
            }
            ProjectVersion version = projectVersionService.getLast(projectId);
            if (version == null) {
                continue;
            }
            List<SqlStatement> statements = sqlStatementService.getByProjectAndVersion(projectId, version.getId());
            if (statements.isEmpty()) {
                continue;
            }
            all.addAll(auditResults(projectId, statements));
        }
        return all;
    }

    /**
     * 按规则ID集合批量加载规则并建立索引
     */
    private Map<Long, AuditRule> ruleIndex(Set<Long> ruleIds) {
        Set<Long> validIds = ruleIds.stream()
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
        if (validIds.isEmpty()) {
            return Map.of();
        }
        return auditRuleMapper.selectList(Wrappers.<AuditRule>lambdaQuery()
                        .in(AuditRule::getId, validIds))
                .stream()
                .collect(Collectors.toMap(AuditRule::getId, Function.identity(), (left, right) -> left));
    }

    private OverviewRuleHitVO.RuleHitCountVO toRuleHitCountVO(Long ruleId,
                                                              Integer hitCount,
                                                              Map<Long, AuditRule> ruleIndex) {
        AuditRule rule = ruleIndex.get(ruleId);
        return OverviewRuleHitVO.RuleHitCountVO.builder()
                .ruleId(ruleId == 0 ? null : ruleId)
                .ruleName(rule == null ? "未知规则" : rule.getName())
                .ruleCode(rule == null ? null : rule.getCode())
                .severity(rule == null ? null : rule.getSeverity())
                .hitCount(hitCount)
                .build();
    }

    /**
     * 计算单项目评分（按 ProjectOverviewServiceImpl 的评分口径，仅取评分与评级）
     */
    private OverviewProjectScoreVO.ProjectScoreVO toProjectScoreVO(Project project, Map<Long, String> projectNames) {
        Long projectId = project.getId();
        ProjectOverviewScoreVO scoreVO = projectOverviewService.getProjectScore(projectId);
        return OverviewProjectScoreVO.ProjectScoreVO.builder()
                .projectId(projectId)
                .projectName(projectNames.getOrDefault(projectId, project.getName()))
                .score(scoreVO.getScore())
                .rating(scoreVO.getRating())
                .issueSqlCount(scoreVO.getIssueSqlCount())
                .build();
    }

    /**
     * 归一化条数上限（1-50，默认 10）
     */
    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 10;
        }
        return Math.min(limit, 50);
    }
}
