package cn.jnu.zhangcs.jsqlint.server.scheduler.task;

import cn.jnu.zhangcs.jsqlint.audit.MatchedAuditRule;
import cn.jnu.zhangcs.jsqlint.audit.SqlAuditResult;
import cn.jnu.zhangcs.jsqlint.audit.SqlAuditTool;
import cn.jnu.zhangcs.jsqlint.audit.model.SqlAuditFact;
import cn.jnu.zhangcs.jsqlint.common.util.FingerprintUtils;
import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStepType;
import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskType;
import cn.jnu.zhangcs.jsqlint.server.entity.*;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditResultMapper;
import cn.jnu.zhangcs.jsqlint.server.service.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 项目审核任务执行器
 * 负责使用 Drools 规则引擎对分析结果进行审核，判断是否存在 SQL 注入风险
 *
 * @author zhangcs
 */
@Slf4j
@Service
public class ProjectAuditTaskExecutor extends ProjectTaskExecutor {

    private final AuditRuleService auditRuleService;

    private final SqlWhitelistService sqlWhitelistService;

    private final SqlStatementService sqlStatementService;

    private final AuditResultMapper auditResultMapper;

    private final TransactionTemplate transactionTemplate;

    private final cn.jnu.zhangcs.jsqlint.server.service.ProjectVersionService projectVersionService;

    public ProjectAuditTaskExecutor(TaskService taskService,
                                    ProjectService projectService,
                                    ProjectVersionStepService projectVersionStepService,
                                    AuditRuleService auditRuleService,
                                    SqlWhitelistService sqlWhitelistService,
                                    SqlStatementService sqlStatementService,
                                    AuditResultMapper auditResultMapper,
                                    TransactionTemplate transactionTemplate,
                                    cn.jnu.zhangcs.jsqlint.server.service.ProjectVersionService projectVersionService) {
        super(taskService, projectService, projectVersionStepService);
        this.auditRuleService = auditRuleService;
        this.sqlWhitelistService = sqlWhitelistService;
        this.sqlStatementService = sqlStatementService;
        this.auditResultMapper = auditResultMapper;
        this.transactionTemplate = transactionTemplate;
        this.projectVersionService = projectVersionService;
    }

    @Override
    public TaskType type() {
        return TaskType.AUDIT;
    }

    @Override
    public TaskResult execute(ScheduledTask task) {
        log.info("开始执行项目审核任务: taskId={}, projectId={}", task.getId(), task.getProjectId());
        Instant start = Instant.now();
        ProjectVersion version = null;

        try {
            // 1. 检查项目信息
            Long projectId = task.getProjectId();
            version = projectService.getLastVersion(projectId);

            if (version == null) {
                return failed(task, null, "项目版本不存在", false);
            }

            if (!projectVersionStepService.isStepSuccess(version.getId(), ProjectVersionStepType.SQL_SCAN)) {
                return failed(task, version, ProjectVersionStepType.RULE_AUDIT, "项目未完成分析", false);
            }

            // 2. 更新版本状态为审核中
            startStep(task, version, ProjectVersionStepType.RULE_AUDIT);
            log.info("更新版本状态为规则审核中: versionId={}", version.getId());

            // 3. 获取项目信息
            Project project = projectService.getById(projectId);
            if (project == null) {
                return failed(task, version, ProjectVersionStepType.RULE_AUDIT, "项目不存在", false);
            }

            log.info("开始审核项目: projectId={}, versionId={}", projectId, version.getId());

            // 4. 获取分析结果
            List<SqlStatement> statements = sqlStatementService.getByProjectAndVersion(projectId, version.getId());
            if (statements.isEmpty()) {
                log.warn("当前版本没有可审核的 SQL 分析结果: projectId={}, versionId={}", projectId, version.getId());
                deleteAuditResults(statements);
                return success(task, version, ProjectVersionStepType.RULE_AUDIT);
            }

            // 5. 获取启用的审核规则
            List<AuditRule> rules = auditRuleService.listEffectiveRules(projectId, true);
            if (rules.isEmpty()) {
                log.warn("系统没有启用的审核规则");
                replaceAuditResults(statements, List.of());
            } else {
                log.info("加载审核规则，规则数量={}", rules.size());

                List<cn.jnu.zhangcs.jsqlint.audit.AuditRule> commonRules = toCommonRules(rules);
                SqlAuditTool.validateRules(commonRules);
                cn.jnu.zhangcs.jsqlint.audit.model.SqlDialect projectDialect =
                        cn.jnu.zhangcs.jsqlint.audit.model.SqlDialect.fromCode(project.getDialect());

                // 6. 增量审核：SQL 内容未变（statementId+sqlHash 相同）的语句跳过重审，
                //    其既有审核结果（含处理状态）原样保留；仅新增/变更 SQL 进入规则评估。
                //    规则集变更会通过 ruleChanged 触发全量兜底。
                Map<String, String> lastAuditedHashes = loadLastAuditedHashes(projectId, version.getId());
                final var versionTime = version.getOperationTime();
                boolean ruleChanged = rules.stream().anyMatch(r -> r.getUpdateTime() != null
                        && versionTime != null
                        && r.getUpdateTime().isAfter(versionTime));
                List<SqlStatement> toAudit = ruleChanged ? statements : filterChangedStatements(statements, lastAuditedHashes);
                int skipped = statements.size() - toAudit.size();
                if (skipped > 0) {
                    log.info("增量审核: projectId={}, 跳过未变更 SQL {} 条, 重审 {} 条（规则变更={}）",
                            projectId, skipped, toAudit.size(), ruleChanged);
                }

                List<AuditResult> auditResults = auditStatements(projectId, toAudit, commonRules, projectDialect);
                replaceAuditResultsIncremental(statements, toAudit, auditResults);
                log.info("执行审核规则完成: projectId={}, versionId={}, sqlCount={}, reAudited={}, issueCount={}",
                        projectId, version.getId(), statements.size(), toAudit.size(), auditResults.size());
            }

            log.info("审核完成: projectId={}, versionId={}", projectId, version.getId());

            // 7. 更新版本状态为审核完成
            return success(task, version, ProjectVersionStepType.RULE_AUDIT);

        } catch (Exception e) {
            log.error("项目审核失败: projectId={}, versionId={}",
                    task.getProjectId(), version != null ? version.getId() : null, e);
            return failed(task, version, ProjectVersionStepType.RULE_AUDIT, "审核异常: " + e.getMessage(), true);
        } finally {
            long duration = Duration.between(start, Instant.now()).toMillis();
            log.info("项目审核任务处理结束，耗时: {}ms", duration);
        }
    }

    private List<AuditResult> auditStatements(
            Long projectId,
            List<SqlStatement> statements,
            List<cn.jnu.zhangcs.jsqlint.audit.AuditRule> rules,
            cn.jnu.zhangcs.jsqlint.audit.model.SqlDialect dialect) {
        List<AuditResult> auditResults = new ArrayList<>();
        for (SqlStatement statement : statements) {
            for (String sql : auditSqlCandidates(statement)) {
                SqlAuditFact fact = new SqlAuditFact(sql, statement.getStatementType());
                fact.setSourceMethod(statement.getStatementId());
                fact.setFilePath(statement.getSourceFile());
                fact.setLineNumber(statement.getSourceLine());
                fact.setDialect(dialect);

                SqlAuditResult result = SqlAuditTool.audit(fact, rules);
                if (!result.hasIssues()) {
                    continue;
                }
                auditResults.addAll(toAuditResults(projectId, statement, result));
            }
        }

        auditResults = collapseSampleVariants(auditResults);

        // 白名单过滤：移除命中白名单的审核结果
        Set<String> whitelistKeys = sqlWhitelistService.loadWhitelistSet(projectId);
        if (!whitelistKeys.isEmpty()) {
            int beforeSize = auditResults.size();
            auditResults.removeIf(result -> {
                String fingerprint = FingerprintUtils.fingerprint(result.getSqlContent());
                String key = fingerprint + ":" + result.getRuleId();
                return whitelistKeys.contains(key);
            });
            int filtered = beforeSize - auditResults.size();
            if (filtered > 0) {
                log.info("白名单过滤: projectId={}, 过滤{}条审核结果", projectId, filtered);
            }
        }

        return auditResults;
    }

    /**
     * 动态 SQL 多渲染变体收敛：同一语句的不同参数采样组合若命中相同规则
     * 且仅字面量采样值不同（规范化后同形），只保留首个渲染变体的结果。
     *
     * <p>判定键为「规范化SQL指纹 + 规则ID」：SQL 中带引号的字符串/数字字面量
     * 统一替换为 {@code ?} 后计算指纹。LIKE('%','name_xxx','%') 这类采样差异
     * 会被归并为一条问题，避免用户处理面板被同规则重复行淹没；
     * 真正结构性差异（不同列/不同条件）规范化后指纹不同，不受影响。</p>
     */
    private List<AuditResult> collapseSampleVariants(List<AuditResult> auditResults) {
        if (auditResults.size() <= 1) {
            return auditResults;
        }
        Set<String> seen = new java.util.HashSet<>();
        List<AuditResult> collapsed = new ArrayList<>(auditResults.size());
        int removed = 0;
        for (AuditResult result : auditResults) {
            String key = normalizedFingerprint(result.getSqlContent()) + ":" + result.getRuleId();
            if (seen.add(key)) {
                collapsed.add(result);
            } else {
                removed++;
            }
        }
        if (removed > 0) {
            log.info("采样变体收敛: 合并{}条同形渲染结果", removed);
        }
        return collapsed;
    }

    /**
     * 规范化 SQL：带引号的字符串字面量与非科学计数数字字面量替换为 {@code ?}，
     * 用于消除参数采样差异后计算指纹。
     */
    private String normalizedFingerprint(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        String normalized = sql
                .replaceAll("'([^']|'')*'", "?")
                .replaceAll("\\b\\d+(\\.\\d+)?\\b", "?");
        return FingerprintUtils.fingerprint(normalized);
    }

    private List<AuditResult> toAuditResults(Long projectId, SqlStatement statement, SqlAuditResult result) {
        List<AuditResult> auditResults = new ArrayList<>();
        if (result.matchedRules().isEmpty()) {
            auditResults.add(toAuditResult(projectId, statement, result, null));
            return auditResults;
        }

        for (MatchedAuditRule matchedRule : result.matchedRules()) {
            auditResults.add(toAuditResult(projectId, statement, result, matchedRule));
        }
        return auditResults;
    }

    private AuditResult toAuditResult(
            Long projectId,
            SqlStatement statement,
            SqlAuditResult result,
            MatchedAuditRule matchedRule) {
        AuditResult auditResult = new AuditResult();
        auditResult.setProjectId(projectId);
        auditResult.setScanId(statement.getId());
        auditResult.setRuleId(matchedRule == null || matchedRule.ruleId() == null ? 0L : matchedRule.ruleId());
        auditResult.setSeverity(firstText(
                matchedRule == null ? null : matchedRule.severity(),
                result.riskLevel().getCode()));
        auditResult.setMessage(buildMessage(result, matchedRule));
        auditResult.setFilePath(statement.getSourceFile());
        auditResult.setLineNumber(statement.getSourceLine());
        auditResult.setSqlContent(result.sql());
        auditResult.setSuggestion(firstText(result.suggestion(), matchedRule == null ? null : matchedRule.suggestion()));
        return auditResult;
    }

    private String buildMessage(SqlAuditResult result, MatchedAuditRule matchedRule) {
        List<String> parts = new ArrayList<>();
        if (matchedRule != null) {
            parts.add(firstText(matchedRule.ruleName(), matchedRule.ruleCode(), matchedRule.droolsRuleName()));
        }
        if (!result.riskFactors().isEmpty()) {
            parts.add(String.join("; ", result.riskFactors()));
        }
        if (parts.isEmpty()) {
            parts.add("SQL审核发现风险");
        }
        return String.join(": ", parts);
    }

    private List<String> auditSqlCandidates(SqlStatement statement) {
        Set<String> candidates = new LinkedHashSet<>();
        Object renderedSqls = statement.getParameterInfo() == null ? null : statement.getParameterInfo().get("renderedSqls");
        if (renderedSqls instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object sql = map.get("sql");
                    if (sql instanceof String value && !value.isBlank()) {
                        candidates.add(value);
                    }
                }
            }
        }
        if (candidates.isEmpty() && statement.getRawSql() != null && !statement.getRawSql().isBlank()) {
            candidates.add(statement.getRawSql());
        }
        return candidates.isEmpty() ? List.of("") : List.copyOf(candidates);
    }

    private void deleteAuditResults(List<SqlStatement> statements) {
        if (statements == null || statements.isEmpty()) {
            return;
        }

        List<Long> scanIds = statements.stream()
                .map(SqlStatement::getId)
                .filter(id -> id != null)
                .toList();
        for (int i = 0; i < scanIds.size(); i += 500) {
            int end = Math.min(i + 500, scanIds.size());
            LambdaQueryWrapper<AuditResult> wrapper = new LambdaQueryWrapper<>();
            wrapper.in(AuditResult::getScanId, scanIds.subList(i, end));
            auditResultMapper.delete(wrapper);
        }
    }

    private void saveAuditResults(List<AuditResult> auditResults) {
        if (auditResults == null || auditResults.isEmpty()) {
            return;
        }
        for (AuditResult auditResult : auditResults) {
            auditResultMapper.insert(auditResult);
        }
    }

    private void replaceAuditResults(List<SqlStatement> statements, List<AuditResult> auditResults) {
        transactionTemplate.executeWithoutResult(status -> {
            // 重新审核前快照既有处理状态，按 SQL指纹+规则ID 继承到新结果：
            // SQL 未变的已处理问题（确认/修复/豁免）不因重扫丢失；SQL 变更视为新问题回 OPEN
            Map<String, AuditResult> handledSnapshot = snapshotHandledStatus(statements);
            deleteAuditResults(statements);
            inheritHandledStatus(auditResults, handledSnapshot);
            saveAuditResults(auditResults);
        });
    }

    /**
     * 加载上一已审核版本的 statementId → sqlHash 映射，作为增量基准。
     *
     * <p>取当前版本之前、最近一个完成过规则审核的版本，读其全部 SQL 语句哈希。
     * 没有历史审核版本（首审）返回空 Map → 全量审核。</p>
     */
    private Map<String, String> loadLastAuditedHashes(Long projectId, Long currentVersionId) {
        List<cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersion> versions =
                projectVersionService.getList(projectId);
        cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersion lastAudited = null;
        for (cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersion candidate : versions) {
            if (candidate.getId() >= currentVersionId) {
                continue;
            }
            if (projectVersionStepService.isStepSuccess(candidate.getId(), ProjectVersionStepType.RULE_AUDIT)) {
                lastAudited = candidate;
                break;
            }
        }
        if (lastAudited == null) {
            return Map.of();
        }
        List<SqlStatement> previousStatements = sqlStatementService.getByProjectAndVersion(projectId, lastAudited.getId());
        Map<String, String> hashes = new HashMap<>();
        for (SqlStatement statement : previousStatements) {
            if (statement.getStatementId() != null && statement.getSqlHash() != null) {
                hashes.putIfAbsent(statement.getStatementId(), statement.getSqlHash());
            }
        }
        return hashes;
    }

    /**
     * 过滤出需要重审的语句：新增（基准中无）或 SQL 内容变化（哈希不同）。
     */
    private List<SqlStatement> filterChangedStatements(List<SqlStatement> statements, Map<String, String> lastAuditedHashes) {
        if (lastAuditedHashes.isEmpty()) {
            return statements;
        }
        return statements.stream()
                .filter(statement -> {
                    if (statement.getStatementId() == null || statement.getSqlHash() == null) {
                        return true;
                    }
                    return !statement.getSqlHash().equals(lastAuditedHashes.get(statement.getStatementId()));
                })
                .toList();
    }

    /**
     * 增量结果落库：只删除并重建"本次重审语句"的结果，未变更语句的既有结果
     * （含处理状态）原样保留。
     */
    private void replaceAuditResultsIncremental(
            List<SqlStatement> allStatements, List<SqlStatement> reAudited, List<AuditResult> newResults) {
        if (reAudited.size() == allStatements.size()) {
            // 全量路径，沿用含状态继承的替换
            replaceAuditResults(allStatements, newResults);
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            Map<String, AuditResult> handledSnapshot = snapshotHandledStatus(reAudited);
            deleteAuditResults(reAudited);
            inheritHandledStatus(newResults, handledSnapshot);
            saveAuditResults(newResults);
        });
    }

    private Map<String, AuditResult> snapshotHandledStatus(List<SqlStatement> statements) {        if (statements == null || statements.isEmpty()) {
            return Map.of();
        }
        List<Long> scanIds = statements.stream()
                .map(SqlStatement::getId)
                .filter(id -> id != null)
                .toList();
        Map<String, AuditResult> handled = new HashMap<>();
        for (int i = 0; i < scanIds.size(); i += 500) {
            List<AuditResult> batch = auditResultMapper.selectList(
                    new LambdaQueryWrapper<AuditResult>()
                            .in(AuditResult::getScanId, scanIds.subList(i, Math.min(i + 500, scanIds.size())))
                            .isNotNull(AuditResult::getStatus)
                            .ne(AuditResult::getStatus, "OPEN"));
            for (AuditResult result : batch) {
                if (result.getSqlContent() == null || result.getRuleId() == null) {
                    continue;
                }
                String key = FingerprintUtils.fingerprint(result.getSqlContent()) + ":" + result.getRuleId();
                handled.putIfAbsent(key, result);
            }
        }
        return handled;
    }

    private void inheritHandledStatus(List<AuditResult> auditResults, Map<String, AuditResult> handledSnapshot) {
        if (auditResults == null || handledSnapshot.isEmpty()) {
            return;
        }
        for (AuditResult result : auditResults) {
            if (result.getSqlContent() == null || result.getRuleId() == null) {
                continue;
            }
            String key = FingerprintUtils.fingerprint(result.getSqlContent()) + ":" + result.getRuleId();
            AuditResult previous = handledSnapshot.get(key);
            if (previous != null) {
                result.setStatus(previous.getStatus());
                result.setHandleUserId(previous.getHandleUserId());
                result.setHandleTime(previous.getHandleTime());
                result.setHandleRemark(previous.getHandleRemark());
            }
        }
    }

    private List<cn.jnu.zhangcs.jsqlint.audit.AuditRule> toCommonRules(List<AuditRule> rules) {
        List<cn.jnu.zhangcs.jsqlint.audit.AuditRule> commonRules = new ArrayList<>(rules.size());
        for (AuditRule rule : rules) {
            commonRules.add(toCommonRule(rule));
        }
        return commonRules;
    }

    private cn.jnu.zhangcs.jsqlint.audit.AuditRule toCommonRule(AuditRule rule) {
        cn.jnu.zhangcs.jsqlint.audit.AuditRule commonRule =
                new cn.jnu.zhangcs.jsqlint.audit.AuditRule();
        commonRule.setId(rule.getId());
        commonRule.setName(rule.getName());
        commonRule.setCode(rule.getCode());
        commonRule.setDescription(rule.getDescription());
        commonRule.setSeverity(rule.getSeverity());
        commonRule.setCategory(rule.getCategory());
        commonRule.setDroolsContent(rule.getDroolsContent());
        commonRule.setSuggestion(rule.getSuggestion());
        commonRule.setEnabled(rule.getEnabled());
        commonRule.setDialect(rule.getDialect());
        commonRule.setCreateUserId(rule.getCreateUserId());
        commonRule.setCreateTime(rule.getCreateTime());
        commonRule.setUpdateTime(rule.getUpdateTime());
        commonRule.setCreateUserName(rule.getCreateUserName());
        commonRule.setSeverityDescription(rule.getSeverityDescription());
        commonRule.setCategoryDescription(rule.getCategoryDescription());
        return commonRule;
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
