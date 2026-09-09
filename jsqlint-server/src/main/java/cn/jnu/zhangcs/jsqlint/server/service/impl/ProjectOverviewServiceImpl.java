package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.*;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditResultMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditRuleMapper;
import cn.jnu.zhangcs.jsqlint.server.model.vo.*;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectOverviewService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectVersionService;
import cn.jnu.zhangcs.jsqlint.server.service.SqlStatementService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProjectOverviewServiceImpl implements ProjectOverviewService {

    private static final int DEFAULT_RULE_HIT_LIMIT = 10;

    private static final int SQL_QUALITY_TREND_LIMIT = 10;

    private static final List<String> SQL_TYPE_ORDER = List.of("SELECT", "INSERT", "UPDATE", "DELETE");

    private final ProjectService projectService;

    private final ProjectVersionService projectVersionService;

    private final SqlStatementService sqlStatementService;

    private final AuditRuleMapper auditRuleMapper;

    private final AuditResultMapper auditResultMapper;

    @Override
    public ProjectOverviewSqlVO getSqlOverview(Long projectId) {
        ProjectVersion version = latestVersion(projectId);
        List<SqlStatement> statements = versionSqlStatements(projectId, version);
        Map<String, Long> countByType = statements.stream()
                .collect(Collectors.groupingBy(
                        statement -> normalizeSqlType(statement.getStatementType()),
                        LinkedHashMap::new,
                        Collectors.counting()));

        return ProjectOverviewSqlVO.builder()
                .projectId(projectId)
                .versionId(versionId(version))
                .totalSqlCount(statements.size())
                .typeCounts(sqlTypeCounts(countByType))
                .build();
    }

    @Override
    public ProjectOverviewRuleVO getRuleOverview(Long projectId) {
        ProjectVersion version = latestVersion(projectId);
        List<AuditRule> rules = effectiveRules(projectId);
        int projectRuleCount = (int) rules.stream()
                .filter(rule -> Objects.equals(rule.getProjectId(), projectId))
                .count();
        int enabledRuleCount = (int) rules.stream()
                .filter(rule -> rule.getEnabled() != null && rule.getEnabled() == 1)
                .count();
        int enabledProjectRuleCount = (int) rules.stream()
                .filter(rule -> Objects.equals(rule.getProjectId(), projectId))
                .filter(rule -> rule.getEnabled() != null && rule.getEnabled() == 1)
                .count();

        return ProjectOverviewRuleVO.builder()
                .projectId(projectId)
                .versionId(versionId(version))
                .totalRuleCount(rules.size())
                .globalRuleCount(rules.size() - projectRuleCount)
                .projectRuleCount(projectRuleCount)
                .enabledRuleCount(enabledRuleCount)
                .enabledProjectRuleCount(enabledProjectRuleCount)
                .build();
    }

    @Override
    public ProjectOverviewRuleHitVO getRuleHitOverview(Long projectId, Integer limit) {
        ProjectVersion version = latestVersion(projectId);
        List<AuditResult> auditResults = versionAuditResults(projectId, version);
        Map<Long, AuditRule> ruleIndex = ruleIndex(auditResults);
        int topLimit = normalizeLimit(limit);

        List<ProjectOverviewRuleHitVO.RuleHitCountVO> highFrequencyRules = auditResults.stream()
                .collect(Collectors.groupingBy(result -> result.getRuleId() == null ? 0L : result.getRuleId()))
                .entrySet()
                .stream()
                .map(entry -> toRuleHitCountVO(entry.getKey(), entry.getValue().size(), ruleIndex))
                .sorted(Comparator
                        .comparing(ProjectOverviewRuleHitVO.RuleHitCountVO::getHitCount, Comparator.reverseOrder())
                        .thenComparing(rule -> rule.getRuleName() == null ? "" : rule.getRuleName()))
                .limit(topLimit)
                .toList();

        return ProjectOverviewRuleHitVO.builder()
                .projectId(projectId)
                .versionId(versionId(version))
                .totalHitCount(auditResults.size())
                .highFrequencyRules(highFrequencyRules)
                .build();
    }

    @Override
    public ProjectOverviewIssueSqlVO getIssueSqlOverview(Long projectId) {
        ProjectVersion version = latestVersion(projectId);
        List<SqlStatement> statements = versionSqlStatements(projectId, version);
        List<AuditResult> auditResults = versionAuditResults(projectId, version, statements);
        int issueSqlCount = (int) auditResults.stream()
                .map(AuditResult::getScanId)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        return ProjectOverviewIssueSqlVO.builder()
                .projectId(projectId)
                .versionId(versionId(version))
                .totalSqlCount(statements.size())
                .issueSqlCount(issueSqlCount)
                .issueCount(auditResults.size())
                .issueSqlRate(issueSqlRate(issueSqlCount, statements.size()))
                .build();
    }

    @Override
    public ProjectOverviewSqlQualityTrendVO getSqlQualityTrend(Long projectId) {
        checkProject(projectId);
        List<ProjectVersion> versions = projectVersionService.getList(projectId)
                .stream()
                .limit(SQL_QUALITY_TREND_LIMIT)
                .sorted(Comparator
                        .comparing(ProjectVersion::getOperationTime, Comparator.nullsLast(LocalDateTime::compareTo))
                        .thenComparing(ProjectVersion::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();

        return ProjectOverviewSqlQualityTrendVO.builder()
                .projectId(projectId)
                .items(versions.stream()
                        .map(version -> toSqlQualityTrendItem(projectId, version))
                        .toList())
                .build();
    }

    @Override
    public ProjectOverviewIssueCategoryVO getIssueCategoryOverview(Long projectId) {
        ProjectVersion version = latestVersion(projectId);
        List<SqlStatement> statements = versionSqlStatements(projectId, version);
        List<AuditResult> auditResults = versionAuditResults(projectId, version, statements);
        Map<Long, AuditRule> ruleIndex = ruleIndex(auditResults);

        return ProjectOverviewIssueCategoryVO.builder()
                .projectId(projectId)
                .versionId(versionId(version))
                .securitySqlCount(countCategorySql(auditResults, ruleIndex, "SECURITY"))
                .performanceSqlCount(countCategorySql(auditResults, ruleIndex, "PERFORMANCE"))
                .qualitySqlCount(countCategorySql(auditResults, ruleIndex, "SQL_QUALITY"))
                .otherSqlCount(countOtherCategorySql(auditResults, ruleIndex))
                .build();
    }

    @Override
    public ProjectOverviewIssueRectificationVO getIssueRectificationRate(Long projectId) {
        checkProject(projectId);
        List<ProjectVersion> versions = projectVersionService.getList(projectId)
                .stream()
                .limit(2)
                .toList();

        ProjectVersion latestVersion = versions.isEmpty() ? null : versions.getFirst();
        ProjectVersion previousVersion = versions.size() < 2 ? null : versions.get(1);
        int latestIssueSqlCount = issueSqlCount(projectId, latestVersion);
        int previousIssueSqlCount = issueSqlCount(projectId, previousVersion);
        int changeCount = latestIssueSqlCount - previousIssueSqlCount;
        BigDecimal increaseRate = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal decreaseRate = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        if (changeCount > 0) {
            increaseRate = changeRate(changeCount, previousIssueSqlCount);
        } else if (changeCount < 0) {
            decreaseRate = changeRate(-changeCount, previousIssueSqlCount);
        }

        return ProjectOverviewIssueRectificationVO.builder()
                .projectId(projectId)
                .latestVersionId(versionId(latestVersion))
                .previousVersionId(versionId(previousVersion))
                .latestIssueSqlCount(latestIssueSqlCount)
                .previousIssueSqlCount(previousIssueSqlCount)
                .changeCount(changeCount)
                .rectificationRate(decreaseRate)
                .increaseRate(increaseRate)
                .decreaseRate(decreaseRate)
                .trend(trend(changeCount))
                .build();
    }

    @Override
    public ProjectOverviewIssueSqlRateVO getIssueSqlRate(Long projectId) {
        checkProject(projectId);
        List<ProjectVersion> versions = projectVersionService.getList(projectId)
                .stream()
                .limit(2)
                .toList();

        ProjectVersion latestVersion = versions.isEmpty() ? null : versions.getFirst();
        ProjectVersion previousVersion = versions.size() < 2 ? null : versions.get(1);
        List<SqlStatement> latestStatements = versionSqlStatements(projectId, latestVersion);
        int latestIssueSqlCount = issueSqlCount(projectId, latestVersion, latestStatements);
        int previousIssueSqlCount = issueSqlCount(projectId, previousVersion);
        int changeCount = latestIssueSqlCount - previousIssueSqlCount;
        BigDecimal increaseRate = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal decreaseRate = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        if (changeCount > 0) {
            increaseRate = changeRate(changeCount, previousIssueSqlCount);
        } else if (changeCount < 0) {
            decreaseRate = changeRate(-changeCount, previousIssueSqlCount);
        }

        return ProjectOverviewIssueSqlRateVO.builder()
                .projectId(projectId)
                .latestVersionId(versionId(latestVersion))
                .previousVersionId(versionId(previousVersion))
                .latestTotalSqlCount(latestStatements.size())
                .latestIssueSqlCount(latestIssueSqlCount)
                .previousIssueSqlCount(previousIssueSqlCount)
                .issueSqlRate(issueSqlRate(latestIssueSqlCount, latestStatements.size()))
                .changeCount(changeCount)
                .increaseRate(increaseRate)
                .decreaseRate(decreaseRate)
                .trend(trend(changeCount))
                .build();
    }

    @Override
    public ProjectOverviewScoreVO getProjectScore(Long projectId) {
        ProjectVersion version = latestVersion(projectId);
        List<SqlStatement> statements = versionSqlStatements(projectId, version);
        List<AuditResult> auditResults = versionAuditResults(projectId, version, statements);
        Map<Long, AuditRule> ruleIndex = ruleIndex(auditResults);

        int totalSqlCount = statements.size();
        int issueSqlCount = (int) auditResults.stream()
                .map(AuditResult::getScanId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        int securitySqlCount = countCategorySql(auditResults, ruleIndex, "SECURITY");
        int performanceSqlCount = countCategorySql(auditResults, ruleIndex, "PERFORMANCE");
        int qualitySqlCount = countCategorySql(auditResults, ruleIndex, "SQL_QUALITY");
        int otherSqlCount = countOtherCategorySql(auditResults, ruleIndex);
        int score = calculateScore(
                totalSqlCount,
                securitySqlCount,
                performanceSqlCount,
                qualitySqlCount,
                otherSqlCount);

        return ProjectOverviewScoreVO.builder()
                .projectId(projectId)
                .versionId(versionId(version))
                .totalSqlCount(totalSqlCount)
                .issueSqlCount(issueSqlCount)
                .securitySqlCount(securitySqlCount)
                .performanceSqlCount(performanceSqlCount)
                .qualitySqlCount(qualitySqlCount)
                .otherSqlCount(otherSqlCount)
                .score(score)
                .rating(rating(score))
                .build();
    }

    private ProjectVersion latestVersion(Long projectId) {
        checkProject(projectId);
        return projectVersionService.getLast(projectId);
    }

    private ProjectOverviewSqlQualityTrendVO.TrendItemVO toSqlQualityTrendItem(Long projectId, ProjectVersion version) {
        List<SqlStatement> statements = versionSqlStatements(projectId, version);
        List<AuditResult> auditResults = versionAuditResults(projectId, version, statements);

        return ProjectOverviewSqlQualityTrendVO.TrendItemVO.builder()
                .versionId(version.getId())
                .ref(version.getRef())
                .commitId(version.getCommitId())
                .operationTime(version.getOperationTime())
                .totalSqlCount(statements.size())
                .criticalSqlCount(countSeveritySql(auditResults, "critical"))
                .highSqlCount(countSeveritySql(auditResults, "high"))
                .warningSqlCount(countSeveritySql(auditResults, "warning"))
                .build();
    }

    private void checkProject(Long projectId) {
        Project project = projectService.getById(projectId);
        if (project == null) {
            throw new BizException("项目不存在");
        }
    }

    private List<SqlStatement> versionSqlStatements(Long projectId, ProjectVersion version) {
        if (version == null) {
            return List.of();
        }
        return sqlStatementService.getByProjectAndVersion(projectId, version.getId());
    }

    private List<AuditResult> versionAuditResults(Long projectId, ProjectVersion version) {
        return versionAuditResults(projectId, version, versionSqlStatements(projectId, version));
    }

    private List<AuditResult> versionAuditResults(Long projectId, ProjectVersion version, List<SqlStatement> statements) {
        if (version == null || statements == null || statements.isEmpty()) {
            return List.of();
        }
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

    private List<AuditRule> effectiveRules(Long projectId) {
        return auditRuleMapper.selectList(Wrappers.<AuditRule>lambdaQuery()
                .and(scope -> scope.isNull(AuditRule::getProjectId).or().eq(AuditRule::getProjectId, projectId)));
    }

    private Map<Long, AuditRule> ruleIndex(List<AuditResult> auditResults) {
        Set<Long> ruleIds = auditResults.stream()
                .map(AuditResult::getRuleId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
        if (ruleIds.isEmpty()) {
            return Map.of();
        }
        return auditRuleMapper.selectList(Wrappers.<AuditRule>lambdaQuery()
                        .in(AuditRule::getId, ruleIds))
                .stream()
                .collect(Collectors.toMap(AuditRule::getId, Function.identity(), (left, right) -> left));
    }

    private ProjectOverviewRuleHitVO.RuleHitCountVO toRuleHitCountVO(Long ruleId,
                                                                     Integer hitCount,
                                                                     Map<Long, AuditRule> ruleIndex) {
        AuditRule rule = ruleIndex.get(ruleId);
        return ProjectOverviewRuleHitVO.RuleHitCountVO.builder()
                .ruleId(ruleId == 0 ? null : ruleId)
                .ruleName(rule == null ? "未知规则" : rule.getName())
                .ruleCode(rule == null ? null : rule.getCode())
                .severity(rule == null ? null : rule.getSeverity())
                .category(rule == null ? null : rule.getCategory())
                .hitCount(hitCount)
                .build();
    }

    private List<ProjectOverviewSqlVO.SqlTypeCountVO> sqlTypeCounts(Map<String, Long> countByType) {
        Map<String, Long> ordered = new LinkedHashMap<>();
        for (String sqlType : SQL_TYPE_ORDER) {
            ordered.put(sqlType, countByType.getOrDefault(sqlType, 0L));
        }
        countByType.entrySet()
                .stream()
                .filter(entry -> !ordered.containsKey(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));

        return ordered.entrySet()
                .stream()
                .map(entry -> ProjectOverviewSqlVO.SqlTypeCountVO.builder()
                        .sqlType(entry.getKey())
                        .count(entry.getValue().intValue())
                        .build())
                .toList();
    }

    private String normalizeSqlType(String type) {
        if (type == null || type.isBlank()) {
            return "UNKNOWN";
        }
        return type.toUpperCase(Locale.ROOT);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_RULE_HIT_LIMIT;
        }
        return Math.min(limit, 50);
    }

    private BigDecimal issueSqlRate(int issueSqlCount, int totalSqlCount) {
        if (totalSqlCount == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(issueSqlCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(totalSqlCount), 2, RoundingMode.HALF_UP);
    }

    private Long versionId(ProjectVersion version) {
        return version == null ? null : version.getId();
    }

    private int issueSqlCount(Long projectId, ProjectVersion version) {
        List<SqlStatement> statements = versionSqlStatements(projectId, version);
        return issueSqlCount(projectId, version, statements);
    }

    private int issueSqlCount(Long projectId, ProjectVersion version, List<SqlStatement> statements) {
        List<AuditResult> auditResults = versionAuditResults(projectId, version, statements);
        return (int) auditResults.stream()
                .map(AuditResult::getScanId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
    }

    private BigDecimal changeRate(int changedCount, int baseCount) {
        if (baseCount == 0) {
            return changedCount == 0
                    ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.valueOf(100).setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(changedCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(baseCount), 2, RoundingMode.HALF_UP);
    }

    private String trend(int changeCount) {
        if (changeCount > 0) {
            return "UP";
        }
        if (changeCount < 0) {
            return "DOWN";
        }
        return "FLAT";
    }

    private int calculateScore(int totalSqlCount,
                               int securitySqlCount,
                               int performanceSqlCount,
                               int qualitySqlCount,
                               int otherSqlCount) {
        return cn.jnu.zhangcs.jsqlint.server.service.ProjectScoreCalculator.score(
                totalSqlCount, securitySqlCount, performanceSqlCount, qualitySqlCount, otherSqlCount);
    }

    private String rating(int score) {
        return cn.jnu.zhangcs.jsqlint.server.service.ProjectScoreCalculator.rating(score);
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
}
