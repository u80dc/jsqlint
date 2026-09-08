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

    public ProjectAuditTaskExecutor(TaskService taskService,
                                    ProjectService projectService,
                                    ProjectVersionStepService projectVersionStepService,
                                    AuditRuleService auditRuleService,
                                    SqlWhitelistService sqlWhitelistService,
                                    SqlStatementService sqlStatementService,
                                    AuditResultMapper auditResultMapper,
                                    TransactionTemplate transactionTemplate) {
        super(taskService, projectService, projectVersionStepService);
        this.auditRuleService = auditRuleService;
        this.sqlWhitelistService = sqlWhitelistService;
        this.sqlStatementService = sqlStatementService;
        this.auditResultMapper = auditResultMapper;
        this.transactionTemplate = transactionTemplate;
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

                // 6. 执行审核（使用 Drools 规则引擎，方言来自项目声明）
                List<AuditResult> auditResults = auditStatements(projectId, statements, commonRules, projectDialect);
                replaceAuditResults(statements, auditResults);
                log.info("执行审核规则完成: projectId={}, versionId={}, sqlCount={}, issueCount={}",
                        projectId, version.getId(), statements.size(), auditResults.size());
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
            deleteAuditResults(statements);
            saveAuditResults(auditResults);
        });
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
