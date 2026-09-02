package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.analysis.AnalyzeResult;
import cn.jnu.zhangcs.jsqlint.analysis.SourceLocation;
import cn.jnu.zhangcs.jsqlint.analysis.node.SqlNodeRenderer;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.infer.InferredParameterValue;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.infer.ParameterValueInferenceResult;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.CallTrace;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.TraceStep;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ValueFlowStep;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditResult;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditRule;
import cn.jnu.zhangcs.jsqlint.server.entity.SqlChangeLog;
import cn.jnu.zhangcs.jsqlint.server.entity.SqlStatement;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditResultMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditRuleMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.SqlChangeLogMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.SqlStatementMapper;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SqlChangeLogVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SqlConstructionDetailVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SqlPropagationPathVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SqlStatementVO;
import cn.jnu.zhangcs.jsqlint.server.service.SqlStatementService;
import cn.jnu.zhangcs.jsqlint.server.util.SqlNodeSerializer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * SQL 语句存储服务实现
 *
 * @author zhangcs
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlStatementServiceImpl implements SqlStatementService {

    private final SqlStatementMapper sqlStatementMapper;

    private final SqlChangeLogMapper sqlChangeLogMapper;

    private final AuditResultMapper auditResultMapper;

    private final AuditRuleMapper auditRuleMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveSqlStatements(List<SqlStatement> statements) {
        if (statements == null || statements.isEmpty()) {
            return;
        }

        // 批量插入
        if (statements.size() <= 100) {
            sqlStatementMapper.insert(statements);
        } else {
            // 分批插入（每次100条）
            for (int i = 0; i < statements.size(); i += 100) {
                int end = Math.min(i + 100, statements.size());
                sqlStatementMapper.insert(statements.subList(i, end));
            }
        }

        log.info("保存 {} 条 SQL 语句", statements.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceVersionAnalysis(Long projectId, Long versionId, AnalyzeResult result) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId must not be null");
        }
        if (versionId == null) {
            throw new IllegalArgumentException("versionId must not be null");
        }
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }

        List<SqlStatement> oldStatements = getByProjectAndVersion(projectId, versionId);
        deleteAuditResults(oldStatements);

        LambdaQueryWrapper<SqlChangeLog> params = Wrappers.<SqlChangeLog>lambdaQuery()
                .eq(SqlChangeLog::getProjectId, projectId)
                .and(w -> w.eq(SqlChangeLog::getFromVersionId, versionId)
                        .or()
                        .eq(SqlChangeLog::getToVersionId, versionId));
        int deletedChanges = sqlChangeLogMapper.delete(params);

        int deletedStatements = sqlStatementMapper.delete(new LambdaQueryWrapper<SqlStatement>()
                .eq(SqlStatement::getProjectId, projectId)
                .eq(SqlStatement::getVersionId, versionId));

        List<SqlStatement> statements = toSqlStatements(projectId, versionId, result);
        saveSqlStatements(statements);

        log.info("替换版本分析结果完成: projectId={}, versionId={}, oldSqlCount={}, newSqlCount={}, deletedChangeCount={}",
                projectId, versionId, deletedStatements, statements.size(), deletedChanges);
    }

    @Override
    public List<SqlStatementVO> getSqlStatementsByVersion(Long versionId) {
        List<SqlStatement> statements = getByVersion(versionId);
        return statements.stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    @Override
    public List<SqlStatementVO> getSqlStatementsByProjectAndVersion(Long projectId, Long versionId) {
        List<SqlStatement> statements = getByProjectAndVersion(projectId, versionId);
        return statements.stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    @Override
    public SqlConstructionDetailVO getConstructionDetail(Long id) {
        SqlStatement statement = sqlStatementMapper.selectById(id);
        if (statement == null) {
            throw new BizException("SQL语句不存在");
        }

        Map<String, Object> parameterInfo = safeMap(statement.getParameterInfo());
        List<Map<String, Object>> renderedSqlMaps = listOfMaps(parameterInfo.get("renderedSqls"));
        List<Map<String, Object>> inferredParameters = listOfMaps(parameterInfo.get("inferred"));
        List<String> diagnostics = diagnostics(statement, inferredParameters, renderedSqlMaps);
        List<AuditResult> auditResults = auditResultMapper.selectList(new LambdaQueryWrapper<AuditResult>()
                .eq(AuditResult::getScanId, statement.getId()));
        Map<Long, AuditRule> ruleIndex = ruleIndex(auditResults);

        return SqlConstructionDetailVO.builder()
                .id(statement.getId())
                .projectId(statement.getProjectId())
                .versionId(statement.getVersionId())
                .definition(toDefinition(statement))
                .constructionSteps(flattenConstructionSteps(statement.getSqlNodeStructure()))
                .parameters(toParameterDetails(statement, inferredParameters))
                .renderedSqls(toRenderedSqls(renderedSqlMaps))
                .auditResults(toAuditResultVOs(auditResults, ruleIndex))
                .diagnostics(diagnostics)
                .sqlNodeStructure(statement.getSqlNodeStructure())
                .metadata(statement.getMetadata())
                .createTime(statement.getCreateTime())
                .build();
    }

    @Override
    public SqlPropagationPathVO getPropagationPaths(Long id, Long versionId) {
        SqlStatement statement = sqlStatementMapper.selectById(id);
        if (statement == null) {
            throw new BizException("SQL语句不存在");
        }
        if (versionId != null && !versionId.equals(statement.getVersionId())) {
            throw new BizException("SQL语句不属于指定版本");
        }

        Map<String, Object> parameterInfo = safeMap(statement.getParameterInfo());
        List<Map<String, Object>> inferredParameters = listOfMaps(parameterInfo.get("inferred"));

        return SqlPropagationPathVO.builder()
                .id(statement.getId())
                .projectId(statement.getProjectId())
                .versionId(statement.getVersionId())
                .statementId(statement.getStatementId())
                .rawSql(statement.getRawSql())
                .parameters(toParameterPropagations(statement, inferredParameters))
                .rawPaths(collectRawPaths(inferredParameters))
                .diagnostics(diagnostics(statement, inferredParameters, List.of()))
                .build();
    }

    private List<SqlPropagationPathVO.ParameterPropagation> toParameterPropagations(
            SqlStatement statement,
            List<Map<String, Object>> inferredParameters) {
        if (inferredParameters.isEmpty()) {
            return List.of();
        }
        return inferredParameters.stream()
                .map(parameter -> {
                    String name = stringValue(parameter.get("name"));
                    Integer index = integerValue(parameter.get("index"));
                    List<Map<String, Object>> possibleValues = listOfMaps(parameter.get("possibleValues"));
                    return SqlPropagationPathVO.ParameterPropagation.builder()
                            .name(firstText(name, index == null ? null : "param" + index))
                            .index(index)
                            .type(stringValue(parameter.get("type")))
                            .bindingType(bindingType(statement.getRawSql(), name))
                            .paths(toPropagationPaths(possibleValues))
                            .build();
                })
                .toList();
    }

    private List<SqlPropagationPathVO.PropagationPath> toPropagationPaths(
            List<Map<String, Object>> possibleValues) {
        if (possibleValues == null || possibleValues.isEmpty()) {
            return List.of();
        }
        List<SqlPropagationPathVO.PropagationPath> result = new ArrayList<>();
        for (Map<String, Object> possibleValue : possibleValues) {
            Map<String, Object> path = safeMap(possibleValue.get("propagationPath"));
            if (path.isEmpty()) {
                continue;
            }
            result.add(SqlPropagationPathVO.PropagationPath.builder()
                    .rootValue(stringValue(path.get("rootValue")))
                    .steps(toCallTraceSteps(listOfMaps(path.get("steps"))))
                    .valueFlow(toValueFlowSteps(listOfMaps(path.get("valueFlow"))))
                    .build());
        }
        return result;
    }

    private List<SqlPropagationPathVO.CallTraceStep> toCallTraceSteps(List<Map<String, Object>> steps) {
        if (steps.isEmpty()) {
            return List.of();
        }
        return steps.stream()
                .map(step -> SqlPropagationPathVO.CallTraceStep.builder()
                        .caller(stringValue(step.get("caller")))
                        .callee(stringValue(step.get("callee")))
                        .callSite(stringValue(step.get("callSite")))
                        .argumentIndex(integerValue(step.get("argumentIndex")))
                        .argument(stringValue(step.get("argument")))
                        .summary(stringValue(step.get("summary")))
                        .build())
                .toList();
    }

    private List<SqlPropagationPathVO.ValueFlowStepVO> toValueFlowSteps(List<Map<String, Object>> steps) {
        if (steps.isEmpty()) {
            return List.of();
        }
        return steps.stream()
                .map(step -> SqlPropagationPathVO.ValueFlowStepVO.builder()
                        .method(stringValue(step.get("method")))
                        .statementIndex(integerValue(step.get("statementIndex")))
                        .statement(stringValue(step.get("statement")))
                        .kind(stringValue(step.get("kind")))
                        .subject(stringValue(step.get("subject")))
                        .value(stringValue(step.get("value")))
                        .summary(stringValue(step.get("summary")))
                        .build())
                .toList();
    }

    private List<Map<String, Object>> collectRawPaths(List<Map<String, Object>> inferredParameters) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> parameter : inferredParameters) {
            for (Map<String, Object> possibleValue : listOfMaps(parameter.get("possibleValues"))) {
                Map<String, Object> path = safeMap(possibleValue.get("propagationPath"));
                if (!path.isEmpty()) {
                    result.add(path);
                }
            }
        }
        return result;
    }

    private List<SqlStatement> getByVersion(Long versionId) {
        return sqlStatementMapper.selectList(new LambdaQueryWrapper<SqlStatement>()
                .eq(SqlStatement::getVersionId, versionId)
                .orderByAsc(SqlStatement::getStatementId));
    }

    @Override
    public List<SqlStatement> getByProjectAndVersion(Long projectId, Long versionId) {
        return sqlStatementMapper.selectList(
                Wrappers.<SqlStatement>lambdaQuery()
                        .eq(SqlStatement::getProjectId, projectId)
                        .eq(SqlStatement::getVersionId, versionId)
                        .orderByAsc(SqlStatement::getStatementId)
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<SqlChangeLogVO> compareTwoVersions(Long projectId, Long fromVersionId, Long toVersionId) {
        // 分别查询两个版本的 SQL 语句，在内存中基于 statementId 与 sqlHash 计算差异
        List<SqlStatement> fromStatements = getByVersion(fromVersionId);
        List<SqlStatement> toStatements = getByVersion(toVersionId);

        Map<String, SqlStatement> fromMap = fromStatements.stream()
                .collect(Collectors.toMap(SqlStatement::getStatementId, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        Map<String, SqlStatement> toMap = toStatements.stream()
                .collect(Collectors.toMap(SqlStatement::getStatementId, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));

        List<SqlChangeLog> changeLogs = new ArrayList<>();
        List<SqlChangeLogVO> changeVOs = new ArrayList<>();

        // 新增：目标版本有、源版本无
        for (Map.Entry<String, SqlStatement> entry : toMap.entrySet()) {
            if (!fromMap.containsKey(entry.getKey())) {
                SqlChangeLog log = buildChangeLog(projectId, fromVersionId, toVersionId,
                        entry.getValue(), "ADDED", null, entry.getValue().getSqlHash());
                changeLogs.add(log);
                changeVOs.add(toChangeLogVO(log));
            }
        }

        // 删除 / 修改：遍历源版本
        for (Map.Entry<String, SqlStatement> entry : fromMap.entrySet()) {
            SqlStatement fromStmt = entry.getValue();
            SqlStatement toStmt = toMap.get(entry.getKey());
            if (toStmt == null) {
                SqlChangeLog log = buildChangeLog(projectId, fromVersionId, toVersionId,
                        fromStmt, "DELETED", fromStmt.getSqlHash(), null);
                changeLogs.add(log);
                changeVOs.add(toChangeLogVO(log));
            } else if (!Objects.equals(fromStmt.getSqlHash(), toStmt.getSqlHash())) {
                SqlChangeLog log = buildChangeLog(projectId, fromVersionId, toVersionId,
                        toStmt, "MODIFIED", fromStmt.getSqlHash(), toStmt.getSqlHash());
                changeLogs.add(log);
                changeVOs.add(toChangeLogVO(log));
            }
        }

        // 批量保存变更日志
        if (!changeLogs.isEmpty()) {
            if (changeLogs.size() <= 100) {
                sqlChangeLogMapper.batchInsert(changeLogs);
            } else {
                for (int i = 0; i < changeLogs.size(); i += 100) {
                    int end = Math.min(i + 100, changeLogs.size());
                    sqlChangeLogMapper.batchInsert(changeLogs.subList(i, end));
                }
            }
        }

        log.info("比较版本 {} 和 {}，发现 {} 处变更", fromVersionId, toVersionId, changeVOs.size());
        return changeVOs;
    }

    /**
     * 构建一条 SQL 变更日志
     */
    private SqlChangeLog buildChangeLog(Long projectId, Long fromVersionId, Long toVersionId,
                                        SqlStatement statement, String changeType,
                                        String fromSqlHash, String toSqlHash) {
        SqlChangeLog log = new SqlChangeLog();
        log.setProjectId(projectId);
        log.setFromVersionId(fromVersionId);
        log.setToVersionId(toVersionId);
        log.setStatementId(statement.getStatementId());
        log.setChangeType(changeType);
        log.setFromSqlHash(fromSqlHash);
        log.setToSqlHash(toSqlHash);

        Map<String, Object> diffSummary = new HashMap<>();
        diffSummary.put("statementType", statement.getStatementType());
        diffSummary.put("isDynamic", statement.getIsDynamic());
        diffSummary.put("paramCount", statement.getParamCount());
        log.setDiffSummary(diffSummary);
        return log;
    }

    @Override
    public List<SqlStatementVO> getSqlStatementsByHash(String sqlHash) {
        List<SqlStatement> statements = sqlStatementMapper.selectList(
                Wrappers.<SqlStatement>lambdaQuery()
                        .eq(SqlStatement::getSqlHash, sqlHash)
                        .orderByDesc(SqlStatement::getCreateTime));
        return statements.stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSqlStatementsByVersion(Long versionId) {
        int count = sqlStatementMapper.delete(
                Wrappers.<SqlStatement>lambdaQuery()
                        .eq(SqlStatement::getVersionId, versionId)
        );
        log.info("删除版本 {} 的 {} 条 SQL 语句", versionId, count);
    }

    @Override
    public List<SqlChangeLogVO> getChangeHistory(Long projectId) {
        LambdaQueryWrapper<SqlChangeLog> params = Wrappers.<SqlChangeLog>lambdaQuery()
                .eq(SqlChangeLog::getProjectId, projectId);
        List<SqlChangeLog> logs = sqlChangeLogMapper.selectList(params);
        return logs.stream()
                .map(this::toChangeLogVO)
                .collect(Collectors.toList());
    }

    @Override
    public List<SqlChangeLogVO> getChangesByVersions(Long projectId, Long fromVersionId, Long toVersionId) {
        LambdaQueryWrapper<SqlChangeLog> params = Wrappers.<SqlChangeLog>lambdaQuery()
                .eq(SqlChangeLog::getProjectId, projectId).
                eq(SqlChangeLog::getFromVersionId, fromVersionId)
                .eq(SqlChangeLog::getToVersionId, toVersionId)
                .orderByDesc(SqlChangeLog::getCreateTime);
        List<SqlChangeLog> logs = sqlChangeLogMapper.selectList(params);
        return logs.stream()
                .map(this::toChangeLogVO)
                .collect(Collectors.toList());
    }

    private SqlConstructionDetailVO.SqlDefinitionVO toDefinition(SqlStatement statement) {
        return SqlConstructionDetailVO.SqlDefinitionVO.builder()
                .statementId(statement.getStatementId())
                .statementType(statement.getStatementType())
                .sqlHash(statement.getSqlHash())
                .rawSql(statement.getRawSql())
                .sourceType(statement.getSourceType())
                .constructionType(constructionType(statement))
                .sourceFile(statement.getSourceFile())
                .sourceLine(statement.getSourceLine())
                .mapperClass(statement.getMapperClass())
                .mapperMethod(statement.getMapperMethod())
                .dynamic(statement.getIsDynamic())
                .parameterCount(statement.getParamCount())
                .build();
    }

    private Map<Long, AuditRule> ruleIndex(List<AuditResult> auditResults) {
        Set<Long> ruleIds = auditResults.stream()
                .map(AuditResult::getRuleId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
        if (ruleIds.isEmpty()) {
            return Map.of();
        }
        return auditRuleMapper.selectBatchIds(ruleIds)
                .stream()
                .collect(Collectors.toMap(AuditRule::getId, Function.identity(), (left, right) -> left));
    }

    private List<SqlConstructionDetailVO.AuditResultVO> toAuditResultVOs(
            List<AuditResult> auditResults,
            Map<Long, AuditRule> ruleIndex) {
        if (auditResults == null || auditResults.isEmpty()) {
            return List.of();
        }
        return auditResults.stream()
                .map(result -> {
                    AuditRule rule = result.getRuleId() == null ? null : ruleIndex.get(result.getRuleId());
                    return SqlConstructionDetailVO.AuditResultVO.builder()
                            .id(result.getId())
                            .ruleId(result.getRuleId())
                            .ruleName(rule == null ? null : rule.getName())
                            .severity(result.getSeverity())
                            .message(result.getMessage())
                            .sqlContent(result.getSqlContent())
                            .suggestion(result.getSuggestion())
                            .filePath(result.getFilePath())
                            .lineNumber(result.getLineNumber())
                            .build();
                })
                .toList();
    }

    private String constructionType(SqlStatement statement) {
        if (Boolean.TRUE.equals(statement.getIsDynamic())) {
            return "DYNAMIC_SQL_NODE";
        }
        if ("XML_SQL".equalsIgnoreCase(statement.getSourceType())) {
            return "MYBATIS_XML";
        }
        if ("PROVIDER_SQL".equalsIgnoreCase(statement.getSourceType())) {
            return "MYBATIS_PROVIDER";
        }
        if ("STATIC_SQL".equalsIgnoreCase(statement.getSourceType())) {
            return "STATIC_SQL";
        }
        return firstText(statement.getSourceType(), "UNKNOWN");
    }

    private List<SqlConstructionDetailVO.ConstructionStepVO> flattenConstructionSteps(Map<String, Object> root) {
        if (root == null || root.isEmpty()) {
            return List.of();
        }

        List<SqlConstructionDetailVO.ConstructionStepVO> steps = new ArrayList<>();
        collectConstructionSteps(root, 0, "root", steps);
        return steps;
    }

    private void collectConstructionSteps(
            Map<String, Object> node,
            int depth,
            String path,
            List<SqlConstructionDetailVO.ConstructionStepVO> steps) {
        if (node == null || node.isEmpty()) {
            return;
        }

        String nodeType = stringValue(node.get("type"));
        Map<String, Object> attributes = constructionAttributes(node);
        steps.add(SqlConstructionDetailVO.ConstructionStepVO.builder()
                .id(path)
                .order(steps.size() + 1)
                .depth(depth)
                .nodeType(nodeType)
                .title(constructionStepTitle(nodeType, node))
                .contribution(constructionContribution(node))
                .condition(constructionCondition(node))
                .dynamic(booleanValue(node.get("isDynamic")))
                .attributes(attributes)
                .build());

        collectChildNode(node.get("contents"), depth + 1, path + ".contents", steps);
        List<Map<String, Object>> children = listOfMaps(node.get("children"));
        for (int i = 0; i < children.size(); i++) {
            collectConstructionSteps(children.get(i), depth + 1, path + ".children[" + i + "]", steps);
        }
        List<Map<String, Object>> whenNodes = listOfMaps(node.get("whenNodes"));
        for (int i = 0; i < whenNodes.size(); i++) {
            collectConstructionSteps(whenNodes.get(i), depth + 1, path + ".when[" + i + "]", steps);
        }
        collectChildNode(node.get("otherwise"), depth + 1, path + ".otherwise", steps);
    }

    private void collectChildNode(Object value, int depth, String path, List<SqlConstructionDetailVO.ConstructionStepVO> steps) {
        if (value instanceof Map<?, ?> map) {
            collectConstructionSteps(toStringObjectMap(map), depth, path, steps);
        }
    }

    private Map<String, Object> constructionAttributes(Map<String, Object> node) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (String key : List.of("test", "collection", "item", "index", "open", "close", "separator",
                "prefix", "suffix", "prefixesToOverride", "suffixesToOverride", "name", "expression")) {
            Object value = node.get(key);
            if (value != null && !"".equals(value)) {
                attributes.put(key, value);
            }
        }
        return attributes;
    }

    private String constructionStepTitle(String nodeType, Map<String, Object> node) {
        if ("IF".equals(nodeType)) {
            return "IF " + stringValue(node.get("test"));
        }
        if ("FOREACH".equals(nodeType)) {
            return "FOREACH " + stringValue(node.get("collection"));
        }
        if ("BIND".equals(nodeType)) {
            return "BIND " + stringValue(node.get("name"));
        }
        if ("TEXT".equals(nodeType) || "STATIC_TEXT".equals(nodeType)) {
            return "SQL片段";
        }
        return nodeType == null || nodeType.isBlank() ? "SQL节点" : nodeType;
    }

    private String constructionContribution(Map<String, Object> node) {
        String text = stringValue(node.get("text"));
        if (text != null && !text.isBlank()) {
            return text;
        }
        if (node.get("open") != null || node.get("close") != null || node.get("separator") != null) {
            return String.format("open=%s, separator=%s, close=%s",
                    defaultText(stringValue(node.get("open")), "-"),
                    defaultText(stringValue(node.get("separator")), "-"),
                    defaultText(stringValue(node.get("close")), "-"));
        }
        if (node.get("prefix") != null || node.get("suffix") != null) {
            return String.format("prefix=%s, suffix=%s",
                    defaultText(stringValue(node.get("prefix")), "-"),
                    defaultText(stringValue(node.get("suffix")), "-"));
        }
        return null;
    }

    private String constructionCondition(Map<String, Object> node) {
        return firstText(
                stringValue(node.get("test")),
                stringValue(node.get("expression")));
    }

    private List<SqlConstructionDetailVO.SqlParameterVO> toParameterDetails(
            SqlStatement statement,
            List<Map<String, Object>> inferredParameters) {
        if (inferredParameters.isEmpty()) {
            return List.of();
        }

        return inferredParameters.stream()
                .map(parameter -> toParameterDetail(statement, parameter))
                .toList();
    }

    private SqlConstructionDetailVO.SqlParameterVO toParameterDetail(SqlStatement statement, Map<String, Object> parameter) {
        String name = stringValue(parameter.get("name"));
        Integer index = integerValue(parameter.get("index"));
        List<Map<String, Object>> possibleValues = listOfMaps(parameter.get("possibleValues"));
        return SqlConstructionDetailVO.SqlParameterVO.builder()
                .name(firstText(name, index == null ? null : "param" + index))
                .index(index)
                .type(stringValue(parameter.get("type")))
                .target(stringValue(parameter.get("target")))
                .bindingType(bindingType(statement.getRawSql(), name))
                .source(parameterSource(possibleValues))
                .possibleValues(possibleValues)
                .propagationPaths(parameterPropagationPaths(possibleValues))
                .diagnostics(stringList(parameter.get("diagnostics")))
                .build();
    }

    private String bindingType(String rawSql, String parameterName) {
        if (rawSql == null || parameterName == null || parameterName.isBlank()) {
            return "UNKNOWN";
        }
        if (rawSql.contains("${" + parameterName + "}")) {
            return "RAW_SUBSTITUTION";
        }
        if (rawSql.contains("#{" + parameterName + "}")) {
            return "PARAMETER_BINDING";
        }
        if (rawSql.contains(parameterName)) {
            return "SQL_TEXT_REFERENCE";
        }
        return "UNKNOWN";
    }

    private String parameterSource(List<Map<String, Object>> possibleValues) {
        if (possibleValues == null || possibleValues.isEmpty()) {
            return "UNKNOWN";
        }
        return possibleValues.stream()
                .map(value -> firstText(stringValue(value.get("origin")), stringValue(value.get("sourceValue"))))
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("UNKNOWN");
    }

    private List<Map<String, Object>> parameterPropagationPaths(List<Map<String, Object>> possibleValues) {
        if (possibleValues == null || possibleValues.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> possibleValue : possibleValues) {
            Map<String, Object> path = safeMap(possibleValue.get("propagationPath"));
            if (!path.isEmpty()) {
                result.add(path);
            }
        }
        return result;
    }

    private List<SqlConstructionDetailVO.RenderedSqlVO> toRenderedSqls(List<Map<String, Object>> renderedSqlMaps) {
        if (renderedSqlMaps.isEmpty()) {
            return List.of();
        }
        return renderedSqlMaps.stream()
                .map(item -> SqlConstructionDetailVO.RenderedSqlVO.builder()
                        .index(integerValue(item.get("index")))
                        .sql(stringValue(item.get("sql")))
                        .parameters(safeMap(item.get("parameters")))
                        .diagnostics(stringList(item.get("diagnostics")))
                        .build())
                .toList();
    }

    private List<String> diagnostics(
            SqlStatement statement,
            List<Map<String, Object>> inferredParameters,
            List<Map<String, Object>> renderedSqlMaps) {
        List<String> result = new ArrayList<>();
        Map<String, Object> metadata = safeMap(statement.getMetadata());
        result.addAll(stringList(metadata.get("diagnostics")));
        inferredParameters.forEach(parameter -> result.addAll(stringList(parameter.get("diagnostics"))));
        renderedSqlMaps.forEach(renderedSql -> result.addAll(stringList(renderedSql.get("diagnostics"))));
        return result.stream()
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .toList();
    }

    private void deleteAuditResults(List<SqlStatement> oldStatements) {
        if (oldStatements == null || oldStatements.isEmpty()) {
            return;
        }

        List<Long> statementIds = oldStatements.stream()
                .map(SqlStatement::getId)
                .filter(id -> id != null)
                .toList();
        if (statementIds.isEmpty()) {
            return;
        }

        for (int i = 0; i < statementIds.size(); i += 500) {
            int end = Math.min(i + 500, statementIds.size());
            LambdaQueryWrapper<AuditResult> wrapper = new LambdaQueryWrapper<>();
            wrapper.in(AuditResult::getScanId, statementIds.subList(i, end));
            auditResultMapper.delete(wrapper);
        }
    }

    private List<SqlStatement> toSqlStatements(Long projectId, Long versionId, AnalyzeResult result) {
        List<AnalyzeResult.SqlStatementResult> sourceStatements = result.getStatements();
        if (sourceStatements == null || sourceStatements.isEmpty()) {
            return List.of();
        }

        List<SqlStatement> statements = new ArrayList<>(sourceStatements.size());
        Map<String, Integer> statementIdCounts = new HashMap<>();
        for (int i = 0; i < sourceStatements.size(); i++) {
            AnalyzeResult.SqlStatementResult source = sourceStatements.get(i);
            SqlStatement statement = toSqlStatement(projectId, versionId, source, i, statementIdCounts);
            statements.add(statement);
        }
        return statements;
    }

    private SqlStatement toSqlStatement(
            Long projectId,
            Long versionId,
            AnalyzeResult.SqlStatementResult source,
            int index,
            Map<String, Integer> statementIdCounts) {
        SourceLocation location = source.getLocation();
        String baseStatementId = firstText(
                source.getStatementId(),
                location == null ? null : location.getFullSignature(),
                "statement-" + index);
        String statementId = uniqueStatementId(baseStatementId, statementIdCounts);
        List<String> auditSqls = auditSqlCandidates(source);

        SqlStatement statement = new SqlStatement();
        statement.setProjectId(projectId);
        statement.setVersionId(versionId);
        statement.setStatementId(limitWithHash(statementId, 500));
        statement.setStatementType(source.getStatementType() == null ? "UNKNOWN" : source.getStatementType().name());
        statement.setSqlHash(sha256(normalizedSqlFingerprint(auditSqls)));
        statement.setRawSql(source.getRawSql());
        statement.setSourceType(source.getSourceType() == null ? "UNKNOWN" : source.getSourceType().name());
        statement.setSourceFile(limitWithHash(location == null ? null : location.getSourceFile(), 500));
        statement.setSourceLine(location == null ? null : zeroToNull(location.getLineNumber()));
        statement.setMapperClass(limitWithHash(location == null ? null : location.getClassName(), 500));
        statement.setMapperMethod(limitWithHash(location == null ? null : location.getMethodName(), 200));
        statement.setSqlNodeStructure(SqlNodeSerializer.serialize(source.getSqlNode()));
        statement.setParameterInfo(parameterInfo(source));
        statement.setMetadata(metadata(source, auditSqls));
        statement.setIsDynamic(source.isDynamic());
        statement.setParamCount(source.getParameterCount());
        return statement;
    }

    private String uniqueStatementId(String statementId, Map<String, Integer> statementIdCounts) {
        int count = statementIdCounts.merge(statementId, 1, Integer::sum);
        if (count == 1) {
            return statementId;
        }
        return statementId + "#" + count;
    }

    private List<String> auditSqlCandidates(AnalyzeResult.SqlStatementResult source) {
        if (source.getRenderedSqls() != null && !source.getRenderedSqls().isEmpty()) {
            return source.getRenderedSqls().stream()
                    .map(SqlNodeRenderer.RenderedSql::sql)
                    .filter(sql -> sql != null && !sql.isBlank())
                    .toList();
        }
        if (source.getRawSql() == null || source.getRawSql().isBlank()) {
            return List.of("");
        }
        return List.of(source.getRawSql());
    }

    private String normalizedSqlFingerprint(List<String> sqls) {
        if (sqls == null || sqls.isEmpty()) {
            return "";
        }
        return sqls.stream()
                .map(this::normalizeSql)
                .sorted()
                .collect(Collectors.joining("\n---\n"));
    }

    private String normalizeSql(String sql) {
        return sql == null ? "" : sql.trim().replaceAll("\\s+", " ");
    }

    private Map<String, Object> parameterInfo(AnalyzeResult.SqlStatementResult source) {
        Map<String, Object> parameterInfo = new LinkedHashMap<>();
        parameterInfo.put("parameterCount", source.getParameterCount());
        parameterInfo.put("inferred", parameterInferences(source.getParameterInferences()));
        parameterInfo.put("renderedSqls", renderedSqls(source.getRenderedSqls()));
        return parameterInfo;
    }

    private List<Map<String, Object>> parameterInferences(List<ParameterValueInferenceResult> inferences) {
        if (inferences == null || inferences.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>(inferences.size());
        for (ParameterValueInferenceResult inference : inferences) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("target", inference.target().displayName());
            item.put("index", inference.target().index());
            item.put("name", inference.target().name());
            item.put("type", inference.target().type());
            item.put("diagnostics", inference.diagnostics());
            item.put("possibleValues", possibleValues(inference.possibleValues()));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> possibleValues(List<InferredParameterValue> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>(values.size());
        for (InferredParameterValue value : values) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("displayValue", value.displayValue());
            item.put("valueType", value.valueType());
            item.put("origin", value.origin().name());
            item.put("summary", value.summary());
            item.put("targetValue", value.targetValue().display());
            item.put("sourceValue", value.sourceValue().display());
            item.put("propagationPath", propagationPath(value.propagationPath()));
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> propagationPath(CallTrace trace) {
        if (trace == null) {
            return Collections.emptyMap();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rootValue", trace.rootValue().display());
        result.put("steps", trace.steps().stream()
                .map(this::traceStep)
                .toList());
        result.put("valueFlow", trace.valueFlow().stream()
                .map(this::valueFlowStep)
                .toList());
        return result;
    }

    private Map<String, Object> traceStep(TraceStep step) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("caller", String.valueOf(step.caller()));
        item.put("callee", String.valueOf(step.callee()));
        item.put("callSite", step.callSite());
        item.put("argumentIndex", step.argumentIndex());
        item.put("argument", step.argument().display());
        item.put("summary", step.summary());
        item.put("valueFlow", step.valueFlow().stream()
                .map(this::valueFlowStep)
                .toList());
        return item;
    }

    private Map<String, Object> valueFlowStep(ValueFlowStep step) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("method", String.valueOf(step.method()));
        item.put("statementIndex", step.statementIndex());
        item.put("statement", step.statement());
        item.put("kind", step.kind().name());
        item.put("subject", step.subject());
        item.put("value", step.value().display());
        item.put("summary", step.summary());
        return item;
    }

    private List<Map<String, Object>> renderedSqls(List<SqlNodeRenderer.RenderedSql> renderedSqls) {
        if (renderedSqls == null || renderedSqls.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>(renderedSqls.size());
        for (int i = 0; i < renderedSqls.size(); i++) {
            SqlNodeRenderer.RenderedSql renderedSql = renderedSqls.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", i);
            item.put("sql", renderedSql.sql());
            item.put("parameters", safeParameterValues(renderedSql.parameters()));
            item.put("diagnostics", renderedSql.diagnostics());
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> safeParameterValues(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        parameters.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> result.put(entry.getKey(), safeJsonValue(entry.getValue())));
        return result;
    }

    private Object safeJsonValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        return String.valueOf(value);
    }

    private Map<String, Object> metadata(AnalyzeResult.SqlStatementResult source, List<String> auditSqls) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("statementId", source.getStatementId());
        metadata.put("dynamic", source.isDynamic());
        metadata.put("hasRenderedSqls", source.hasRenderedSqls());
        metadata.put("renderedSqlCount", source.getRenderedSqlCount());
        metadata.put("diagnostics", source.getDiagnostics() == null ? List.of() : source.getDiagnostics());
        metadata.put("auditSqlCount", auditSqls == null ? 0 : auditSqls.size());

        AnalyzeResult.Metadata sourceMetadata = source.getMetadata();
        if (sourceMetadata != null) {
            metadata.put("callers", sourceMetadata.getCallers());
            metadata.put("tags", sourceMetadata.getTags());
            metadata.put("extra", safeExtra(sourceMetadata.getExtra()));
        }
        return metadata;
    }

    private Map<String, Object> safeExtra(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        extra.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> result.put(entry.getKey(), safeJsonValue(entry.getValue())));
        return result;
    }

    private Integer zeroToNull(int value) {
        return value <= 0 ? null : value;
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

    private String limitWithHash(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        String hash = sha256(value).substring(0, 16);
        int prefixLength = Math.max(0, maxLength - hash.length() - 1);
        return value.substring(0, prefixLength) + "#" + hash;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }
    }

    private Map<String, Object> safeMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return toStringObjectMap(map);
        }
        return Collections.emptyMap();
    }

    private Map<String, Object> toStringObjectMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), value);
            }
        });
        return result;
    }

    private List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add(toStringObjectMap(map));
            }
        }
        return result;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item != null)
                .map(String::valueOf)
                .toList();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text);
        }
        return null;
    }

    private String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /**
     * 转换为 VO
     */
    private SqlStatementVO toVO(SqlStatement entity) {
        if (entity == null) {
            return null;
        }

        return SqlStatementVO.builder()
                .id(entity.getId())
                .projectId(entity.getProjectId())
                .versionId(entity.getVersionId())
                .statementId(entity.getStatementId())
                .statementType(entity.getStatementType())
                .sqlHash(entity.getSqlHash())
                .rawSql(entity.getRawSql())
                .sourceType(entity.getSourceType())
                .sourceFile(entity.getSourceFile())
                .sourceLine(entity.getSourceLine())
                .mapperClass(entity.getMapperClass())
                .mapperMethod(entity.getMapperMethod())
                .sqlNodeStructure(entity.getSqlNodeStructure())
                .parameterInfo(entity.getParameterInfo())
                .metadata(entity.getMetadata())
                .isDynamic(entity.getIsDynamic())
                .paramCount(entity.getParamCount())
                .createTime(entity.getCreateTime())
                .build();
    }

    /**
     * 转换为变更日志 VO
     */
    private SqlChangeLogVO toChangeLogVO(SqlChangeLog entity) {
        if (entity == null) {
            return null;
        }

        return SqlChangeLogVO.builder()
                .id(entity.getId())
                .projectId(entity.getProjectId())
                .fromVersionId(entity.getFromVersionId())
                .toVersionId(entity.getToVersionId())
                .statementId(entity.getStatementId())
                .changeType(entity.getChangeType())
                .fromSqlHash(entity.getFromSqlHash())
                .toSqlHash(entity.getToSqlHash())
                .diffSummary(entity.getDiffSummary())
                .createTime(entity.getCreateTime())
                .build();
    }
}
