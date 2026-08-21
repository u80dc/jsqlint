package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SqlChangeLogVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SqlConstructionDetailVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SqlPropagationPathVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SqlStatementVO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.SqlStatementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SqlStatementController} 单元测试（standalone MockMvc，service 全部 Mock）。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class SqlStatementControllerTest {

    @Mock
    private SqlStatementService sqlStatementService;

    @InjectMocks
    private SqlStatementController sqlStatementController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(sqlStatementController)
                .setControllerAdvice(new GlobalExceptionHandler())
                // standalone 默认 StringHttpMessageConverter 为 ISO-8859-1，中文响应会丢字，需显式指定 UTF-8
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new JacksonJsonHttpMessageConverter())
                // standalone 默认不注册安全相关解析器，@AuthenticationPrincipal 会被兜底绑定成空对象，需显式注册
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 模拟已认证用户：standalone MockMvc 无安全过滤器，
     * {@code @AuthenticationPrincipal} 依赖 SecurityContextHolder 中的认证信息。
     */
    private UsernamePasswordAuthenticationToken login(Long userId) {
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(new LoginUser("admin", userId), "password");
        SecurityContextHolder.getContext().setAuthentication(token);
        return token;
    }

    private SqlStatementVO statement(Long id, String statementType, Boolean isDynamic) {
        return SqlStatementVO.builder()
                .id(id)
                .projectId(5L)
                .versionId(2L)
                .statementId("UserMapper.selectById")
                .statementType(statementType)
                .sqlHash("abc123")
                .rawSql("SELECT * FROM user WHERE id = ?")
                .sourceType("MAPPER")
                .sourceFile("UserMapper.xml")
                .sourceLine(10)
                .isDynamic(isDynamic)
                .paramCount(1)
                .build();
    }

    @Test
    void listByVersion_returnsStatementList() throws Exception {
        when(sqlStatementService.getSqlStatementsByVersion(2L))
                .thenReturn(List.of(statement(1L, "SELECT", true)));

        mockMvc.perform(get("/api/sql-statements/version/2").principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].statementId").value("UserMapper.selectById"))
                .andExpect(jsonPath("$[0].statementType").value("SELECT"))
                .andExpect(jsonPath("$[0].isDynamic").value(true));
    }

    @Test
    void listByProjectAndVersion_returnsStatementList() throws Exception {
        when(sqlStatementService.getSqlStatementsByProjectAndVersion(5L, 2L))
                .thenReturn(List.of(statement(1L, "SELECT", true), statement(2L, "INSERT", false)));

        mockMvc.perform(get("/api/sql-statements/project/5/version/2").principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].statementType").value("INSERT"));

        verify(sqlStatementService).getSqlStatementsByProjectAndVersion(5L, 2L);
    }

    @Test
    void getConstructionDetail_returnsDefinitionAndSteps() throws Exception {
        when(sqlStatementService.getConstructionDetail(1L)).thenReturn(SqlConstructionDetailVO.builder()
                .id(1L)
                .projectId(5L)
                .versionId(2L)
                .definition(SqlConstructionDetailVO.SqlDefinitionVO.builder()
                        .statementId("UserMapper.selectById")
                        .statementType("SELECT")
                        .sqlHash("abc123")
                        .sourceFile("UserMapper.xml")
                        .sourceLine(10)
                        .dynamic(true)
                        .parameterCount(1)
                        .build())
                .constructionSteps(List.of(SqlConstructionDetailVO.ConstructionStepVO.builder()
                        .id("root")
                        .order(0)
                        .nodeType("MixedSqlNode")
                        .title("动态SQL根节点")
                        .build()))
                .parameters(List.of())
                .renderedSqls(List.of())
                .auditResults(List.of())
                .build());

        mockMvc.perform(get("/api/sql-statements/1/construction").principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.definition.statementId").value("UserMapper.selectById"))
                .andExpect(jsonPath("$.definition.dynamic").value(true))
                .andExpect(jsonPath("$.constructionSteps[0].nodeType").value("MixedSqlNode"))
                .andExpect(jsonPath("$.constructionSteps[0].title").value("动态SQL根节点"));
    }

    @Test
    void getPropagationPaths_withVersionId_returnsPaths() throws Exception {
        when(sqlStatementService.getPropagationPaths(1L, 2L)).thenReturn(SqlPropagationPathVO.builder()
                .id(1L)
                .projectId(5L)
                .versionId(2L)
                .statementId("UserMapper.selectById")
                .rawSql("SELECT * FROM user WHERE id = ?")
                .parameters(List.of())
                .diagnostics(List.of())
                .build());

        mockMvc.perform(get("/api/sql-statements/1/propagation-paths")
                        .param("versionId", "2")
                        .principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.statementId").value("UserMapper.selectById"))
                .andExpect(jsonPath("$.rawSql").value("SELECT * FROM user WHERE id = ?"));

        verify(sqlStatementService).getPropagationPaths(1L, 2L);
    }

    @Test
    void compareVersions_requiredParams_returnsChangeList() throws Exception {
        when(sqlStatementService.compareTwoVersions(5L, 1L, 2L)).thenReturn(List.of(SqlChangeLogVO.builder()
                .id(1L)
                .projectId(5L)
                .fromVersionId(1L)
                .toVersionId(2L)
                .statementId("UserMapper.selectById")
                .changeType("MODIFIED")
                .fromSqlHash("old111")
                .toSqlHash("abc123")
                .build()));

        mockMvc.perform(get("/api/sql-statements/compare")
                        .param("projectId", "5")
                        .param("fromVersionId", "1")
                        .param("toVersionId", "2")
                        .principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].changeType").value("MODIFIED"))
                .andExpect(jsonPath("$[0].fromVersionId").value(1))
                .andExpect(jsonPath("$[0].toVersionId").value(2))
                .andExpect(jsonPath("$[0].toSqlHash").value("abc123"));

        verify(sqlStatementService).compareTwoVersions(5L, 1L, 2L);
    }

    @Test
    void listByHash_returnsMatchingStatements() throws Exception {
        when(sqlStatementService.getSqlStatementsByHash("abc123"))
                .thenReturn(List.of(statement(1L, "SELECT", true)));

        mockMvc.perform(get("/api/sql-statements/by-hash/abc123").principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sqlHash").value("abc123"));

        verify(sqlStatementService).getSqlStatementsByHash("abc123");
    }

    @Test
    void getChangeHistory_returnsChangeList() throws Exception {
        when(sqlStatementService.getChangeHistory(5L)).thenReturn(List.of(SqlChangeLogVO.builder()
                .id(2L)
                .projectId(5L)
                .fromVersionId(1L)
                .toVersionId(2L)
                .statementId("UserMapper.selectById")
                .changeType("ADDED")
                .build()));

        mockMvc.perform(get("/api/sql-statements/changes/project/5").principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].changeType").value("ADDED"));

        verify(sqlStatementService).getChangeHistory(5L);
    }

    @Test
    void getChangesByVersions_requiredParams_returnsChangeList() throws Exception {
        when(sqlStatementService.getChangesByVersions(5L, 1L, 2L)).thenReturn(List.of(SqlChangeLogVO.builder()
                .id(3L)
                .projectId(5L)
                .fromVersionId(1L)
                .toVersionId(2L)
                .statementId("UserMapper.selectById")
                .changeType("DELETED")
                .build()));

        mockMvc.perform(get("/api/sql-statements/changes")
                        .param("projectId", "5")
                        .param("fromVersionId", "1")
                        .param("toVersionId", "2")
                        .principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].changeType").value("DELETED"));

        verify(sqlStatementService).getChangesByVersions(5L, 1L, 2L);
    }

    @Test
    void deleteByVersion_returnsSuccessMessageMap() throws Exception {
        mockMvc.perform(delete("/api/sql-statements/version/2").principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("删除成功"));

        verify(sqlStatementService).deleteSqlStatementsByVersion(2L);
    }

    @Test
    void getStatistics_returnsComputedCounts() throws Exception {
        // 2 条动态 SELECT + 1 条静态 INSERT，验证控制器内的统计逻辑
        when(sqlStatementService.getSqlStatementsByVersion(2L)).thenReturn(List.of(
                statement(1L, "SELECT", true),
                statement(2L, "SELECT", true),
                statement(3L, "INSERT", false)));

        mockMvc.perform(get("/api/sql-statements/statistics/version/2").principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.dynamicCount").value(2))
                .andExpect(jsonPath("$.staticCount").value(1))
                .andExpect(jsonPath("$.typeCount.SELECT").value(2))
                .andExpect(jsonPath("$.typeCount.INSERT").value(1));
    }

    @Test
    void listByVersion_bizExceptionMappedTo400() throws Exception {
        when(sqlStatementService.getSqlStatementsByVersion(99L)).thenThrow(new BizException("版本不存在"));

        mockMvc.perform(get("/api/sql-statements/version/99").principal(login(7L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("版本不存在"));
    }
}
