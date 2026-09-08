package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditResult;
import cn.jnu.zhangcs.jsqlint.server.entity.SqlStatement;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditResultMapper;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SqlChangeLogVO;
import cn.jnu.zhangcs.jsqlint.server.service.SqlStatementService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link VersionDiffController} 单元测试（standalone MockMvc，service 全部 Mock）。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class VersionDiffControllerTest {

    @Mock
    private SqlStatementService sqlStatementService;

    @Mock
    private AuditResultMapper auditResultMapper;

    @InjectMocks
    private VersionDiffController controller;

    private MockMvc mockMvc;

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                AuditResult.class);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new JacksonJsonHttpMessageConverter())
                .build();
    }

    private SqlChangeLogVO change(String statementId, String changeType) {
        return SqlChangeLogVO.builder()
                .statementId(statementId)
                .changeType(changeType)
                .build();
    }

    private SqlStatement statement(Long id, String statementId) {
        SqlStatement statement = new SqlStatement();
        statement.setId(id);
        statement.setStatementId(statementId);
        statement.setSqlHash("hash-" + statementId);
        return statement;
    }

    private AuditResult issue(String severity) {
        AuditResult result = new AuditResult();
        result.setSeverity(severity);
        return result;
    }

    @Test
    void diff_aggregatesCountsAndRiskDelta() throws Exception {
        when(sqlStatementService.compareTwoVersions(5L, 1L, 2L)).thenReturn(List.of(
                change("UserMapper.selectById", "ADDED"),
                change("UserMapper.insert", "MODIFIED"),
                change("UserMapper.delete", "DELETED")));
        // 目标版本全部语句；风险增量只统计 ADDED/MODIFIED 的 statementId
        when(sqlStatementService.getByProjectAndVersion(5L, 2L)).thenReturn(List.of(
                statement(11L, "UserMapper.selectById"),
                statement(12L, "UserMapper.insert"),
                statement(13L, "UserMapper.delete")));
        when(auditResultMapper.selectList(any())).thenReturn(List.of(
                issue("critical"), issue("critical"), issue("warning")));

        mockMvc.perform(get("/api/projects/5/versions/diff")
                        .param("from", "1")
                        .param("to", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addedCount").value(1))
                .andExpect(jsonPath("$.modifiedCount").value(1))
                .andExpect(jsonPath("$.deletedCount").value(1))
                .andExpect(jsonPath("$.changes.length()").value(3))
                .andExpect(jsonPath("$.newCriticalCount").value(2))
                .andExpect(jsonPath("$.newWarningCount").value(1))
                .andExpect(jsonPath("$.newIssueCount").value(3));
    }

    @Test
    void diff_noChanges_returnsZeroRisk() throws Exception {
        when(sqlStatementService.compareTwoVersions(5L, 1L, 2L)).thenReturn(List.of());

        mockMvc.perform(get("/api/projects/5/versions/diff")
                        .param("from", "1")
                        .param("to", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addedCount").value(0))
                .andExpect(jsonPath("$.newIssueCount").value(0));
    }

    @Test
    void diff_sameVersionIds_returns400() throws Exception {
        mockMvc.perform(get("/api/projects/5/versions/diff")
                        .param("from", "2")
                        .param("to", "2"))
                .andExpect(status().isBadRequest());
    }
}
