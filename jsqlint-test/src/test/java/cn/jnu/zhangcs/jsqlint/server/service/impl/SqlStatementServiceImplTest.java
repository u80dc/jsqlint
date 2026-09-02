package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.analysis.AnalyzeResult;
import cn.jnu.zhangcs.jsqlint.analysis.SourceLocation;
import cn.jnu.zhangcs.jsqlint.analysis.SqlSourceType;
import cn.jnu.zhangcs.jsqlint.analysis.StatementType;
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
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SqlStatementServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class SqlStatementServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        // wrapper.eq/in/orderBy(...) 需要实体列缓存，纯单元测试下需手动初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SqlStatement.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SqlChangeLog.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AuditResult.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AuditRule.class);
    }

    @Mock
    private SqlStatementMapper sqlStatementMapper;

    @Mock
    private SqlChangeLogMapper sqlChangeLogMapper;

    @Mock
    private AuditResultMapper auditResultMapper;

    @Mock
    private AuditRuleMapper auditRuleMapper;

    @InjectMocks
    private SqlStatementServiceImpl sqlStatementService;

    private SqlStatement statement(long id, String statementId, String sqlHash) {
        SqlStatement statement = new SqlStatement();
        statement.setId(id);
        statement.setProjectId(1L);
        statement.setVersionId(2L);
        statement.setStatementId(statementId);
        statement.setStatementType("SELECT");
        statement.setSqlHash(sqlHash);
        statement.setIsDynamic(false);
        statement.setParamCount(0);
        statement.setCreateTime(LocalDateTime.of(2026, 1, 1, 10, 0));
        return statement;
    }

    private AnalyzeResult.SqlStatementResult statementResult(String statementId, String rawSql) {
        return AnalyzeResult.SqlStatementResult.builder()
                .statementId(statementId)
                .statementType(StatementType.SELECT)
                .sourceType(SqlSourceType.XML_SQL)
                .location(SourceLocation.of("com.example.UserMapper", "selectById",
                        "java.util.List", List.of("java.lang.Long"), "UserMapper.java", 42))
                .rawSql(rawSql)
                .build();
    }

    private Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    @Test
    void saveSqlStatements_emptyList_noInsert() {
        sqlStatementService.saveSqlStatements(null);
        sqlStatementService.saveSqlStatements(List.of());

        verify(sqlStatementMapper, never()).insert(anyList());
    }

    @Test
    void saveSqlStatements_over100Statements_batchInsert() {
        List<SqlStatement> statements = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            statements.add(new SqlStatement());
        }

        sqlStatementService.saveSqlStatements(statements);

        // 101 条按 100 条一批拆分为 2 次插入
        verify(sqlStatementMapper, times(2)).insert(anyList());
    }

    @Test
    void replaceVersionAnalysis_nullArgument_throwsException() {
        AnalyzeResult result = AnalyzeResult.success(List.of());

        assertThatThrownBy(() -> sqlStatementService.replaceVersionAnalysis(null, 2L, result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("projectId must not be null");
        assertThatThrownBy(() -> sqlStatementService.replaceVersionAnalysis(1L, null, result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("versionId must not be null");
        assertThatThrownBy(() -> sqlStatementService.replaceVersionAnalysis(1L, 2L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("result must not be null");
    }

    @Test
    void replaceVersionAnalysis_clearsOldDataAndSavesNewStatements() {
        when(sqlStatementMapper.selectList(any())).thenReturn(List.of(statement(100L, "old.sql", "h0")));
        List<SqlStatement> saved = new ArrayList<>();
        // insert(Collection) 批量重载返回 List<BatchResult>
        doAnswer(inv -> {
            saved.addAll(inv.getArgument(0));
            return List.of();
        }).when(sqlStatementMapper).insert(anyList());

        sqlStatementService.replaceVersionAnalysis(1L, 2L,
                AnalyzeResult.success(List.of(statementResult("com.UserMapper.selectById", "SELECT * FROM users"))));

        verify(auditResultMapper).delete(any(Wrapper.class));
        verify(sqlChangeLogMapper).delete(any(Wrapper.class));
        verify(sqlStatementMapper).delete(any(Wrapper.class));
        assertThat(saved).hasSize(1);
        SqlStatement s = saved.get(0);
        assertThat(s.getProjectId()).isEqualTo(1L);
        assertThat(s.getVersionId()).isEqualTo(2L);
        assertThat(s.getStatementId()).isEqualTo("com.UserMapper.selectById");
        assertThat(s.getStatementType()).isEqualTo("SELECT");
        assertThat(s.getSourceType()).isEqualTo("XML_SQL");
        assertThat(s.getSourceFile()).isEqualTo("UserMapper.java");
        assertThat(s.getSourceLine()).isEqualTo(42);
        assertThat(s.getMapperClass()).isEqualTo("com.example.UserMapper");
        assertThat(s.getMapperMethod()).isEqualTo("selectById");
        assertThat(s.getSqlHash()).hasSize(64);
        assertThat(s.getIsDynamic()).isFalse();
        assertThat(s.getParamCount()).isZero();
    }

    @Test
    void replaceVersionAnalysis_duplicateStatementId_appendsSuffix() {
        when(sqlStatementMapper.selectList(any())).thenReturn(List.of());
        List<SqlStatement> saved = new ArrayList<>();
        doAnswer(inv -> {
            saved.addAll(inv.getArgument(0));
            return List.of();
        }).when(sqlStatementMapper).insert(anyList());

        sqlStatementService.replaceVersionAnalysis(1L, 2L, AnalyzeResult.success(List.of(
                statementResult("dup.sql", "SELECT 1"),
                statementResult("dup.sql", "SELECT 2"))));

        assertThat(saved).extracting("statementId").containsExactly("dup.sql", "dup.sql#2");
    }

    @Test
    void getSqlStatementsByVersion_convertsToVo() {
        when(sqlStatementMapper.selectList(any())).thenReturn(List.of(
                statement(1L, "A", "h1"), statement(2L, "B", "h2")));

        var result = sqlStatementService.getSqlStatementsByVersion(2L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getStatementId()).isEqualTo("A");
        assertThat(result.get(0).getSqlHash()).isEqualTo("h1");
        assertThat(result.get(1).getRawSql()).isNull();
        assertThat(result.get(1).getCreateTime()).isNotNull();
    }

    @Test
    void getByProjectAndVersion_delegatesToMapper() {
        List<SqlStatement> statements = List.of(statement(1L, "A", "h1"));
        when(sqlStatementMapper.selectList(any())).thenReturn(statements);

        assertThat(sqlStatementService.getByProjectAndVersion(1L, 2L)).isSameAs(statements);
    }

    @Test
    void getConstructionDetail_statementNotFound_throwsException() {
        when(sqlStatementMapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> sqlStatementService.getConstructionDetail(9L))
                .isInstanceOf(BizException.class)
                .hasMessage("SQL语句不存在");
    }

    @Test
    void getConstructionDetail_fillsDefinitionStepsParametersAndAuditResults() {
        SqlStatement statement = statement(1L, "com.example.UserMapper.selectById", "h1");
        statement.setRawSql("SELECT * FROM t WHERE id = #{id} OR name = ${name}");
        statement.setSourceType("XML_SQL");
        statement.setSourceFile("UserMapper.java");
        statement.setSourceLine(10);
        statement.setMapperClass("com.example.UserMapper");
        statement.setMapperMethod("selectById");
        statement.setIsDynamic(true);
        statement.setParamCount(2);
        statement.setSqlNodeStructure(map(
                "type", "MIXED", "isDynamic", true,
                "children", List.of(map("type", "IF", "test", "id != null",
                        "contents", map("type", "TEXT", "text", "AND id = #{id}")))));
        statement.setParameterInfo(map(
                "inferred", List.of(
                        map("name", "id", "index", 0, "type", "java.lang.Long",
                                "possibleValues", List.of(map("origin", "CONSTANT", "sourceValue", "'1'")),
                                "diagnostics", List.of("参数id推断")),
                        map("name", "name", "index", 1, "type", "java.lang.String"),
                        map("index", 2)),
                "renderedSqls", List.of(map("index", 0, "sql", "SELECT * FROM t WHERE id = 1",
                        "parameters", Map.of(), "diagnostics", List.of("渲染完成")))));
        statement.setMetadata(map("diagnostics", List.of("元数据诊断")));
        when(sqlStatementMapper.selectById(1L)).thenReturn(statement);
        AuditResult result = new AuditResult();
        result.setId(5L);
        result.setRuleId(10L);
        result.setSeverity("high");
        result.setMessage("存在风险");
        when(auditResultMapper.selectList(any())).thenReturn(List.of(result));
        AuditRule rule = new AuditRule();
        rule.setId(10L);
        rule.setName("规则10");
        when(auditRuleMapper.selectBatchIds(any())).thenReturn(List.of(rule));

        var vo = sqlStatementService.getConstructionDetail(1L);

        var definition = vo.getDefinition();
        assertThat(definition.getStatementId()).isEqualTo("com.example.UserMapper.selectById");
        // 动态 SQL 优先判定构造类型
        assertThat(definition.getConstructionType()).isEqualTo("DYNAMIC_SQL_NODE");
        assertThat(definition.getDynamic()).isTrue();
        assertThat(definition.getParameterCount()).isEqualTo(2);

        assertThat(vo.getConstructionSteps()).extracting("nodeType").containsExactly("MIXED", "IF", "TEXT");
        var ifStep = vo.getConstructionSteps().get(1);
        assertThat(ifStep.getTitle()).isEqualTo("IF id != null");
        assertThat(ifStep.getCondition()).isEqualTo("id != null");
        assertThat(ifStep.getAttributes()).containsEntry("test", "id != null");
        var textStep = vo.getConstructionSteps().get(2);
        assertThat(textStep.getTitle()).isEqualTo("SQL片段");
        assertThat(textStep.getContribution()).isEqualTo("AND id = #{id}");
        assertThat(textStep.getDepth()).isEqualTo(2);

        assertThat(vo.getParameters()).hasSize(3);
        assertThat(vo.getParameters().get(0).getName()).isEqualTo("id");
        assertThat(vo.getParameters().get(0).getBindingType()).isEqualTo("PARAMETER_BINDING");
        assertThat(vo.getParameters().get(0).getSource()).isEqualTo("CONSTANT");
        assertThat(vo.getParameters().get(1).getBindingType()).isEqualTo("RAW_SUBSTITUTION");
        // 名称缺失时回退 param + 下标，SQL 中无引用为 UNKNOWN
        assertThat(vo.getParameters().get(2).getName()).isEqualTo("param2");
        assertThat(vo.getParameters().get(2).getBindingType()).isEqualTo("UNKNOWN");

        assertThat(vo.getRenderedSqls()).hasSize(1);
        assertThat(vo.getRenderedSqls().get(0).getSql()).isEqualTo("SELECT * FROM t WHERE id = 1");

        assertThat(vo.getAuditResults()).hasSize(1);
        assertThat(vo.getAuditResults().get(0).getRuleName()).isEqualTo("规则10");
        assertThat(vo.getAuditResults().get(0).getSeverity()).isEqualTo("high");

        assertThat(vo.getDiagnostics()).containsExactly("元数据诊断", "参数id推断", "渲染完成");
    }

    @Test
    void getPropagationPaths_statementNotFound_throwsException() {
        when(sqlStatementMapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> sqlStatementService.getPropagationPaths(9L, null))
                .isInstanceOf(BizException.class)
                .hasMessage("SQL语句不存在");
    }

    @Test
    void getPropagationPaths_notInSpecifiedVersion_throwsException() {
        when(sqlStatementMapper.selectById(1L)).thenReturn(statement(1L, "A", "h1"));

        assertThatThrownBy(() -> sqlStatementService.getPropagationPaths(1L, 3L))
                .isInstanceOf(BizException.class)
                .hasMessage("SQL语句不属于指定版本");
    }

    @Test
    void getPropagationPaths_fillsParametersAndCallChain() {
        SqlStatement statement = statement(1L, "com.example.UserMapper.selectById", "h1");
        statement.setRawSql("SELECT * FROM t WHERE name = ${name}");
        Map<String, Object> step = map("caller", "Caller.service", "callee", "Mapper.select",
                "callSite", "$r3 = ...", "argumentIndex", 0, "argument", "'admin'",
                "summary", "Caller.service -> Mapper.select[0]");
        Map<String, Object> flow = map("method", "Caller.service", "statementIndex", 3,
                "statement", "sql = $r3", "kind", "LOCAL_ASSIGN", "subject", "sql",
                "value", "'admin'", "summary", "LOCAL_ASSIGN sql => 'admin'");
        Map<String, Object> path = map("rootValue", "'admin'", "steps", List.of(step), "valueFlow", List.of(flow));
        statement.setParameterInfo(map("inferred", List.of(
                map("name", "name", "index", 1, "type", "java.lang.String",
                        "possibleValues", List.of(map("propagationPath", path))))));
        statement.setMetadata(map("diagnostics", List.of("污点分析完成")));
        when(sqlStatementMapper.selectById(1L)).thenReturn(statement);

        var vo = sqlStatementService.getPropagationPaths(1L, 2L);

        assertThat(vo.getStatementId()).isEqualTo("com.example.UserMapper.selectById");
        assertThat(vo.getRawSql()).isEqualTo("SELECT * FROM t WHERE name = ${name}");
        assertThat(vo.getParameters()).hasSize(1);
        var parameter = vo.getParameters().get(0);
        assertThat(parameter.getName()).isEqualTo("name");
        assertThat(parameter.getBindingType()).isEqualTo("RAW_SUBSTITUTION");
        assertThat(parameter.getPaths()).hasSize(1);
        assertThat(parameter.getPaths().get(0).getRootValue()).isEqualTo("'admin'");
        assertThat(parameter.getPaths().get(0).getSteps()).hasSize(1);
        assertThat(parameter.getPaths().get(0).getSteps().get(0).getCaller()).isEqualTo("Caller.service");
        assertThat(parameter.getPaths().get(0).getValueFlow()).hasSize(1);
        assertThat(parameter.getPaths().get(0).getValueFlow().get(0).getKind()).isEqualTo("LOCAL_ASSIGN");
        assertThat(vo.getRawPaths()).hasSize(1);
        assertThat(vo.getDiagnostics()).containsExactly("污点分析完成");
    }

    @Test
    void compareTwoVersions_detectsAddedModifiedDeleted() {
        when(sqlStatementMapper.selectList(any())).thenReturn(
                List.of(statement(1L, "A", "h1"), statement(2L, "B", "h2"), statement(3L, "C", "h3")),
                List.of(statement(1L, "A", "h1"), statement(2L, "B", "h9"), statement(4L, "D", "h4")));

        List<SqlChangeLogVO> vos = sqlStatementService.compareTwoVersions(1L, 1L, 2L);

        // 新增在前，随后按源版本顺序输出修改与删除
        assertThat(vos).extracting("changeType").containsExactly("ADDED", "MODIFIED", "DELETED");
        assertThat(vos).extracting("statementId").containsExactly("D", "B", "C");
        assertThat(vos.get(0).getFromSqlHash()).isNull();
        assertThat(vos.get(0).getToSqlHash()).isEqualTo("h4");
        assertThat(vos.get(1).getFromSqlHash()).isEqualTo("h2");
        assertThat(vos.get(1).getToSqlHash()).isEqualTo("h9");
        assertThat(vos.get(2).getToSqlHash()).isNull();
        assertThat(vos.get(0).getDiffSummary())
                .containsEntry("statementType", "SELECT")
                .containsEntry("isDynamic", false);
        verify(sqlChangeLogMapper).batchInsert(anyList());
    }

    @Test
    void compareTwoVersions_noChange_writesNoLog() {
        when(sqlStatementMapper.selectList(any())).thenReturn(
                List.of(statement(1L, "A", "h1")),
                List.of(statement(1L, "A", "h1")));

        assertThat(sqlStatementService.compareTwoVersions(1L, 1L, 2L)).isEmpty();

        verify(sqlChangeLogMapper, never()).batchInsert(anyList());
    }

    @Test
    void getSqlStatementsByHash_convertsToVo() {
        when(sqlStatementMapper.selectList(any())).thenReturn(List.of(statement(1L, "A", "h1")));

        var result = sqlStatementService.getSqlStatementsByHash("h1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatementId()).isEqualTo("A");
        assertThat(result.get(0).getSqlHash()).isEqualTo("h1");
    }

    @Test
    void deleteSqlStatementsByVersion_delegatesToMapper() {
        sqlStatementService.deleteSqlStatementsByVersion(2L);

        verify(sqlStatementMapper).delete(any(Wrapper.class));
    }

    @Test
    void getChangeHistory_convertsToVo() {
        SqlChangeLog log = new SqlChangeLog();
        log.setId(5L);
        log.setProjectId(1L);
        log.setFromVersionId(1L);
        log.setToVersionId(2L);
        log.setStatementId("A");
        log.setChangeType("MODIFIED");
        log.setFromSqlHash("h1");
        log.setToSqlHash("h9");
        log.setDiffSummary(Map.of("statementType", "SELECT"));
        when(sqlChangeLogMapper.selectList(any())).thenReturn(List.of(log));

        var result = sqlStatementService.getChangeHistory(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(5L);
        assertThat(result.get(0).getChangeType()).isEqualTo("MODIFIED");
        assertThat(result.get(0).getDiffSummary()).containsEntry("statementType", "SELECT");
    }

    @Test
    void getChangesByVersions_convertsToVo() {
        SqlChangeLog log = new SqlChangeLog();
        log.setId(5L);
        log.setChangeType("ADDED");
        when(sqlChangeLogMapper.selectList(any())).thenReturn(List.of(log));

        var result = sqlStatementService.getChangesByVersions(1L, 1L, 2L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getChangeType()).isEqualTo("ADDED");
    }
}
