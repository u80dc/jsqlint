package cn.jnu.zhangcs.jsqlint.server.scheduler.task;

import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStepType;
import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskType;
import cn.jnu.zhangcs.jsqlint.server.entity.*;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditResultMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditRuleMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.ProjectReportMapper;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectVersionStepService;
import cn.jnu.zhangcs.jsqlint.server.service.SqlStatementService;
import cn.jnu.zhangcs.jsqlint.server.service.TaskService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProjectReportTaskExecutor extends ProjectTaskExecutor {

    private static final int TOP_LIMIT = 10;

    private static final int HIGH_RISK_DETAIL_LIMIT = 50;

    private final SqlStatementService sqlStatementService;

    private final AuditResultMapper auditResultMapper;

    private final AuditRuleMapper auditRuleMapper;

    private final ProjectReportMapper projectReportMapper;

    private final TransactionTemplate transactionTemplate;

    public ProjectReportTaskExecutor(TaskService taskService,
                                     ProjectService projectService,
                                     ProjectVersionStepService projectVersionStepService,
                                     SqlStatementService sqlStatementService,
                                     AuditResultMapper auditResultMapper,
                                     AuditRuleMapper auditRuleMapper,
                                     ProjectReportMapper projectReportMapper,
                                     TransactionTemplate transactionTemplate) {
        super(taskService, projectService, projectVersionStepService);
        this.sqlStatementService = sqlStatementService;
        this.auditResultMapper = auditResultMapper;
        this.auditRuleMapper = auditRuleMapper;
        this.projectReportMapper = projectReportMapper;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public TaskType type() {
        return TaskType.REPORT;
    }

    @Override
    public TaskResult execute(ScheduledTask task) {
        Long projectId = task.getProjectId();
        log.info("开始执行项目报告任务: taskId={}, projectId={}", task.getId(), projectId);

        ProjectVersion version = null;
        try {
            version = projectService.getLastVersion(projectId);
            if (version == null) {
                return failed(task, null, "项目版本不存在", false);
            }
            if (!projectVersionStepService.isStepSuccess(version.getId(), ProjectVersionStepType.RULE_AUDIT)) {
                return failed(task, version, ProjectVersionStepType.GENERATE_REPORT, "项目未完成审核", false);
            }

            startStep(task, version, ProjectVersionStepType.GENERATE_REPORT);

            ProjectReport report = buildReport(task, version);
            saveReport(report);

            log.info("项目报告生成完成: projectId={}, versionId={}, score={}, issueSqlCount={}, issueCount={}",
                    projectId, version.getId(), report.getScore(), report.getIssueSqlCount(), report.getIssueCount());
            return success(task, version, ProjectVersionStepType.GENERATE_REPORT);
        } catch (Exception e) {
            log.error("项目报告生成失败: projectId={}, versionId={}",
                    projectId, version == null ? null : version.getId(), e);
            return failed(task, version, ProjectVersionStepType.GENERATE_REPORT, "报告生成异常: " + e.getMessage(), true);
        }
    }

    private ProjectReport buildReport(ScheduledTask task, ProjectVersion version) {
        Long projectId = task.getProjectId();
        List<SqlStatement> statements = sqlStatementService.getByProjectAndVersion(projectId, version.getId());
        List<Long> scanIds = statements.stream()
                .map(SqlStatement::getId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, SqlStatement> statementIndex = statements.stream()
                .filter(statement -> statement.getId() != null)
                .collect(Collectors.toMap(SqlStatement::getId, Function.identity(), (left, right) -> left));

        List<AuditResult> auditResults = scanIds.isEmpty()
                ? List.of()
                : auditResultMapper.selectList(Wrappers.<AuditResult>lambdaQuery()
                .eq(AuditResult::getProjectId, projectId)
                .in(AuditResult::getScanId, scanIds));

        Set<Long> ruleIds = ruleIds(auditResults);
        Map<Long, AuditRule> ruleIndex = ruleIds.isEmpty()
                ? Map.of()
                : auditRuleMapper.selectList(Wrappers.<AuditRule>lambdaQuery().in(AuditRule::getId, ruleIds)).stream()
                .collect(Collectors.toMap(AuditRule::getId, Function.identity(), (left, right) -> left));

        ProjectReport report = new ProjectReport();
        report.setProjectId(projectId);
        report.setVersionId(version.getId());
        report.setTaskId(task.getId());
        report.setTotalSqlCount(statements.size());
        report.setIssueCount(auditResults.size());
        report.setIssueSqlCount(distinctScanCount(auditResults));
        report.setIssueSqlRate(issueSqlRate(report.getIssueSqlCount(), report.getTotalSqlCount()));
        report.setCriticalCount(countSeverity(auditResults, "critical"));
        report.setCriticalSqlCount(countSeveritySql(auditResults, "critical"));
        report.setHighCount(countSeverity(auditResults, "high"));
        report.setWarningCount(countSeverity(auditResults, "warning"));
        report.setInfoCount(countSeverity(auditResults, "info"));
        report.setSecurityIssueSqlCount(countCategorySql(auditResults, ruleIndex, "SECURITY"));
        report.setPerformanceIssueSqlCount(countCategorySql(auditResults, ruleIndex, "PERFORMANCE"));
        report.setQualityIssueSqlCount(countCategorySql(auditResults, ruleIndex, "SQL_QUALITY"));
        report.setOtherIssueSqlCount(countOtherCategorySql(auditResults, ruleIndex));
        report.setTopRuleJson(topRules(auditResults, ruleIndex));
        report.setTopFileJson(topFiles(auditResults, statementIndex));
        report.setHighRiskSqlJson(highRiskSqlDetails(auditResults, ruleIndex, statementIndex));

        int score = score(report);
        report.setScore(score);
        report.setRating(rating(score));
        report.setGeneratedTime(LocalDateTime.now());
        return report;
    }

    private Set<Long> ruleIds(List<AuditResult> auditResults) {
        return auditResults.stream()
                .map(AuditResult::getRuleId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
    }

    private int distinctScanCount(List<AuditResult> auditResults) {
        return (int) auditResults.stream()
                .map(AuditResult::getScanId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
    }

    private BigDecimal issueSqlRate(Integer issueSqlCount, Integer totalSqlCount) {
        if (totalSqlCount == null || totalSqlCount == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        int issueCount = issueSqlCount == null ? 0 : issueSqlCount;
        return BigDecimal.valueOf(issueCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(totalSqlCount), 2, RoundingMode.HALF_UP);
    }

    private int countSeverity(List<AuditResult> auditResults, String severity) {
        return (int) auditResults.stream()
                .filter(result -> severity.equalsIgnoreCase(result.getSeverity()))
                .count();
    }

    private int countSeveritySql(List<AuditResult> auditResults, String severity) {
        return (int) auditResults.stream()
                .filter(result -> severity.equalsIgnoreCase(result.getSeverity()))
                .map(AuditResult::getScanId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
    }

    private int countCategorySql(List<AuditResult> auditResults, Map<Long, AuditRule> ruleIndex, String category) {
        return (int) auditResults.stream()
                .filter(result -> category.equalsIgnoreCase(categoryOf(result, ruleIndex)))
                .map(AuditResult::getScanId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
    }

    private int countOtherCategorySql(List<AuditResult> auditResults, Map<Long, AuditRule> ruleIndex) {
        Set<String> knownCategories = Set.of("SECURITY", "PERFORMANCE", "SQL_QUALITY");
        return (int) auditResults.stream()
                .filter(result -> !knownCategories.contains(categoryOf(result, ruleIndex).toUpperCase(Locale.ROOT)))
                .map(AuditResult::getScanId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
    }

    private String categoryOf(AuditResult result, Map<Long, AuditRule> ruleIndex) {
        if (result.getRuleId() == null) {
            return "OTHER";
        }
        AuditRule rule = ruleIndex.get(result.getRuleId());
        if (rule == null || rule.getCategory() == null || rule.getCategory().isBlank()) {
            return "OTHER";
        }
        return rule.getCategory();
    }

    private List<Map<String, Object>> topRules(List<AuditResult> auditResults, Map<Long, AuditRule> ruleIndex) {
        Map<Long, List<AuditResult>> grouped = auditResults.stream()
                .collect(Collectors.groupingBy(result -> result.getRuleId() == null ? 0L : result.getRuleId()));
        return grouped.entrySet().stream()
                .map(entry -> topRuleItem(entry.getKey(), entry.getValue(), ruleIndex))
                .sorted(Comparator
                        .<Map<String, Object>>comparingInt(item -> (Integer) item.get("issueCount")).reversed()
                        .thenComparing(item -> String.valueOf(item.get("ruleName"))))
                .limit(TOP_LIMIT)
                .toList();
    }

    private Map<String, Object> topRuleItem(Long ruleId, List<AuditResult> results, Map<Long, AuditRule> ruleIndex) {
        AuditRule rule = ruleIndex.get(ruleId);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("ruleId", ruleId);
        item.put("ruleName", rule == null ? "未知规则" : rule.getName());
        item.put("ruleCode", rule == null ? null : rule.getCode());
        item.put("severity", rule == null ? mostSevere(results) : rule.getSeverity());
        item.put("category", rule == null ? "OTHER" : categoryOf(results.getFirst(), ruleIndex));
        item.put("issueCount", results.size());
        item.put("issueSqlCount", distinctScanCount(results));
        return item;
    }

    private List<Map<String, Object>> topFiles(List<AuditResult> auditResults, Map<Long, SqlStatement> statementIndex) {
        Map<String, List<AuditResult>> grouped = auditResults.stream()
                .collect(Collectors.groupingBy(result -> filePathOf(result, statementIndex)));
        return grouped.entrySet().stream()
                .map(entry -> topFileItem(entry.getKey(), entry.getValue()))
                .sorted(Comparator
                        .<Map<String, Object>>comparingInt(item -> (Integer) item.get("issueCount")).reversed()
                        .thenComparing(item -> String.valueOf(item.get("filePath"))))
                .limit(TOP_LIMIT)
                .toList();
    }

    private Map<String, Object> topFileItem(String filePath, List<AuditResult> results) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("filePath", filePath);
        item.put("issueCount", results.size());
        item.put("issueSqlCount", distinctScanCount(results));
        item.put("criticalCount", countSeverity(results, "critical"));
        item.put("highCount", countSeverity(results, "high"));
        return item;
    }

    private List<Map<String, Object>> highRiskSqlDetails(
            List<AuditResult> auditResults,
            Map<Long, AuditRule> ruleIndex,
            Map<Long, SqlStatement> statementIndex) {
        return auditResults.stream()
                .filter(result -> isHighRisk(result.getSeverity()))
                .sorted(Comparator
                        .<AuditResult>comparingInt(result -> severityRank(result.getSeverity())).reversed()
                        .thenComparing(result -> Optional.ofNullable(result.getFilePath()).orElse(""))
                        .thenComparing(result -> Optional.ofNullable(result.getLineNumber()).orElse(0)))
                .limit(HIGH_RISK_DETAIL_LIMIT)
                .map(result -> highRiskSqlItem(result, ruleIndex, statementIndex))
                .toList();
    }

    private Map<String, Object> highRiskSqlItem(
            AuditResult result,
            Map<Long, AuditRule> ruleIndex,
            Map<Long, SqlStatement> statementIndex) {
        AuditRule rule = result.getRuleId() == null ? null : ruleIndex.get(result.getRuleId());
        SqlStatement statement = result.getScanId() == null ? null : statementIndex.get(result.getScanId());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("resultId", result.getId());
        item.put("scanId", result.getScanId());
        item.put("ruleId", result.getRuleId());
        item.put("ruleName", rule == null ? null : rule.getName());
        item.put("ruleCode", rule == null ? null : rule.getCode());
        item.put("severity", result.getSeverity());
        item.put("category", categoryOf(result, ruleIndex));
        item.put("filePath", filePathOf(result, statementIndex));
        item.put("lineNumber", result.getLineNumber());
        item.put("statementId", statement == null ? null : statement.getStatementId());
        item.put("mapperClass", statement == null ? null : statement.getMapperClass());
        item.put("mapperMethod", statement == null ? null : statement.getMapperMethod());
        item.put("sqlContent", result.getSqlContent());
        item.put("message", result.getMessage());
        item.put("suggestion", result.getSuggestion());
        return item;
    }

    private String filePathOf(AuditResult result, Map<Long, SqlStatement> statementIndex) {
        if (result.getFilePath() != null && !result.getFilePath().isBlank()) {
            return result.getFilePath();
        }
        SqlStatement statement = result.getScanId() == null ? null : statementIndex.get(result.getScanId());
        if (statement != null && statement.getSourceFile() != null && !statement.getSourceFile().isBlank()) {
            return statement.getSourceFile();
        }
        return "UNKNOWN";
    }

    private boolean isHighRisk(String severity) {
        return "critical".equalsIgnoreCase(severity) || "high".equalsIgnoreCase(severity);
    }

    private int severityRank(String severity) {
        if ("critical".equalsIgnoreCase(severity)) {
            return 4;
        }
        if ("high".equalsIgnoreCase(severity)) {
            return 3;
        }
        if ("warning".equalsIgnoreCase(severity)) {
            return 2;
        }
        if ("info".equalsIgnoreCase(severity)) {
            return 1;
        }
        return 0;
    }

    private String mostSevere(List<AuditResult> results) {
        return results.stream()
                .map(AuditResult::getSeverity)
                .filter(Objects::nonNull)
                .max(Comparator.comparingInt(this::severityRank))
                .orElse(null);
    }

    /**
     * 统一评分口径：与项目概览一致，按问题 SQL 数（scanId 去重）× 规则分类
     * 加权后归一化扣分，报告内已按分类统计 issueSqlCount，直接复用。
     */
    private int score(ProjectReport report) {
        return cn.jnu.zhangcs.jsqlint.server.service.ProjectScoreCalculator.score(
                report.getTotalSqlCount(),
                report.getSecurityIssueSqlCount(),
                report.getPerformanceIssueSqlCount(),
                report.getQualityIssueSqlCount(),
                report.getOtherIssueSqlCount());
    }

    private String rating(int score) {
        return cn.jnu.zhangcs.jsqlint.server.service.ProjectScoreCalculator.rating(score);
    }

    private void saveReport(ProjectReport report) {
        transactionTemplate.executeWithoutResult(status -> {
            projectReportMapper.delete(Wrappers.<ProjectReport>lambdaQuery()
                    .eq(ProjectReport::getProjectId, report.getProjectId())
                    .eq(ProjectReport::getVersionId, report.getVersionId()));
            projectReportMapper.insert(report);
        });
    }
}
