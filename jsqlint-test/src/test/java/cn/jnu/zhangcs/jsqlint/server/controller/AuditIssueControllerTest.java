package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.enums.IssueStatus;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditResult;
import cn.jnu.zhangcs.jsqlint.server.entity.SqlStatement;
import cn.jnu.zhangcs.jsqlint.server.entity.SqlWhitelist;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditResultMapper;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.AuditRuleService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import cn.jnu.zhangcs.jsqlint.server.service.SqlStatementService;
import cn.jnu.zhangcs.jsqlint.server.service.SqlWhitelistService;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AuditIssueController} 单元测试（standalone MockMvc，service 全部 Mock）。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class AuditIssueControllerTest {

    @Mock
    private AuditResultMapper auditResultMapper;

    @Mock
    private AuditRuleService auditRuleService;

    @Mock
    private ProjectService projectService;

    @Mock
    private UserService userService;

    @Mock
    private SqlStatementService sqlStatementService;

    @Mock
    private SqlWhitelistService sqlWhitelistService;

    @InjectMocks
    private AuditIssueController controller;

    private MockMvc mockMvc;

    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        // wrapper.eq(...) 需要实体列缓存，纯单元测试下需手动初始化
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                AuditResult.class);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new JacksonJsonHttpMessageConverter())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAsProjectAdmin(Long userId, Long projectId) {
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(new LoginUser("admin", userId), "password");
        SecurityContextHolder.getContext().setAuthentication(token);
        when(projectService.isAdmin(projectId, userId)).thenReturn(true);
    }

    private AuditResult result(Long id, Long projectId, String status) {
        AuditResult result = new AuditResult();
        result.setId(id);
        result.setProjectId(projectId);
        result.setScanId(10L);
        result.setRuleId(3L);
        result.setSeverity("high");
        result.setMessage("DELETE 缺少 WHERE");
        result.setSqlContent("DELETE FROM users");
        result.setStatus(status);
        result.setCreateTime(LocalDateTime.of(2026, 9, 8, 10, 0));
        return result;
    }

    @Test
    void listIssues_returnsPageWithRuleName() throws Exception {
        Page<AuditResult> page = new Page<>(1, 20);
        page.setRecords(List.of(result(1L, 5L, "OPEN")));
        page.setTotal(1);
        when(auditResultMapper.selectPage(any(), any())).thenReturn(page);
        cn.jnu.zhangcs.jsqlint.server.entity.AuditRule rule =
                new cn.jnu.zhangcs.jsqlint.server.entity.AuditRule();
        rule.setId(3L);
        rule.setName("DELETE 无 WHERE 检查");
        when(auditRuleService.getRulesByIds(any())).thenReturn(List.of(rule));

        mockMvc.perform(get("/api/projects/5/issues").param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list.length()").value(1))
                .andExpect(jsonPath("$.list[0].ruleName").value("DELETE 无 WHERE 检查"))
                .andExpect(jsonPath("$.list[0].statusDescription").value("待处理"));
    }

    @Test
    void statistics_fillsZeroCounts() throws Exception {
        when(auditResultMapper.selectList(any())).thenReturn(List.of(
                result(1L, 5L, "OPEN"), result(2L, 5L, "OPEN"), result(3L, 5L, "RESOLVED")));

        mockMvc.perform(get("/api/projects/5/issues/statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.OPEN").value(2))
                .andExpect(jsonPath("$.RESOLVED").value(1))
                .andExpect(jsonPath("$.CONFIRMED").value(0))
                .andExpect(jsonPath("$.DISMISSED").value(0));
    }

    @Test
    void countsByScan_groupsByScanIdWithOpenCount() throws Exception {
        AuditResult openA = result(1L, 5L, "OPEN");
        openA.setScanId(10L);
        AuditResult resolvedA = result(2L, 5L, "RESOLVED");
        resolvedA.setScanId(10L);
        AuditResult openB = result(3L, 5L, "OPEN");
        openB.setScanId(11L);
        // 无版本过滤：直接全项目聚合
        when(auditResultMapper.selectList(any())).thenReturn(List.of(openA, resolvedA, openB));

        mockMvc.perform(get("/api/projects/5/issues/counts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].scanId").value(10))
                .andExpect(jsonPath("$[0].total").value(2))
                .andExpect(jsonPath("$[0].openCount").value(1))
                .andExpect(jsonPath("$[1].scanId").value(11))
                .andExpect(jsonPath("$[1].total").value(1))
                .andExpect(jsonPath("$[1].openCount").value(1));
    }

    @Test
    void countsByScan_withVersion_filtersByStatementIds() throws Exception {
        SqlStatement statement = new SqlStatement();
        statement.setId(10L);
        when(sqlStatementService.getByProjectAndVersion(5L, 3L)).thenReturn(List.of(statement));
        when(auditResultMapper.selectList(any())).thenReturn(List.of(result(1L, 5L, "OPEN")));

        mockMvc.perform(get("/api/projects/5/issues/counts").param("versionId", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].scanId").value(10))
                .andExpect(jsonPath("$[0].total").value(1))
                .andExpect(jsonPath("$[0].openCount").value(1));
    }

    @Test
    void countsByScan_emptyVersion_returnsEmptyList() throws Exception {
        when(sqlStatementService.getByProjectAndVersion(5L, 3L)).thenReturn(List.of());

        mockMvc.perform(get("/api/projects/5/issues/counts").param("versionId", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void transit_confirm_setsHandlerAndRemark() throws Exception {
        loginAsProjectAdmin(7L, 5L);
        when(auditResultMapper.selectById(30L)).thenReturn(result(30L, 5L, "OPEN"));

        mockMvc.perform(put("/api/projects/5/issues/30/status")
                        .principal(SecurityContextHolder.getContext().getAuthentication())
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"status\":\"CONFIRMED\",\"remark\":\"已确认需要修复\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditResult> captor = ArgumentCaptor.forClass(AuditResult.class);
        verify(auditResultMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("CONFIRMED");
        assertThat(captor.getValue().getHandleUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getHandleRemark()).isEqualTo("已确认需要修复");
        // 非豁免流转不触发白名单
        verify(sqlWhitelistService, never()).create(anyLong(), any(), anyLong());
    }

    @Test
    void transit_dismissWithWhitelist_writesWhitelist() throws Exception {
        loginAsProjectAdmin(7L, 5L);
        when(auditResultMapper.selectById(31L)).thenReturn(result(31L, 5L, "OPEN"));
        SqlWhitelist created = new SqlWhitelist();
        created.setId(99L);
        when(sqlWhitelistService.create(eq(5L), any(), eq(7L))).thenReturn(created);

        mockMvc.perform(put("/api/projects/5/issues/31/status")
                        .principal(SecurityContextHolder.getContext().getAuthentication())
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"status\":\"DISMISSED\",\"remark\":\"误报\",\"addToWhitelist\":true}"))
                .andExpect(status().isOk());

        verify(sqlWhitelistService).create(eq(5L), any(), eq(7L));
    }

    @Test
    void transit_dismissWithoutWhitelistFlag_skipsWhitelist() throws Exception {
        loginAsProjectAdmin(7L, 5L);
        when(auditResultMapper.selectById(32L)).thenReturn(result(32L, 5L, "OPEN"));

        mockMvc.perform(put("/api/projects/5/issues/32/status")
                        .principal(SecurityContextHolder.getContext().getAuthentication())
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"status\":\"DISMISSED\",\"remark\":\"本次豁免\"}"))
                .andExpect(status().isOk());

        verify(sqlWhitelistService, never()).create(anyLong(), any(), anyLong());
    }

    @Test
    void transit_reopen_clearsHandleInfo() throws Exception {
        loginAsProjectAdmin(7L, 5L);
        AuditResult confirmed = result(33L, 5L, "CONFIRMED");
        confirmed.setHandleUserId(7L);
        confirmed.setHandleTime(LocalDateTime.of(2026, 9, 8, 9, 0));
        confirmed.setHandleRemark("旧备注");
        when(auditResultMapper.selectById(33L)).thenReturn(confirmed);

        mockMvc.perform(put("/api/projects/5/issues/33/status")
                        .principal(SecurityContextHolder.getContext().getAuthentication())
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditResult> captor = ArgumentCaptor.forClass(AuditResult.class);
        verify(auditResultMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("OPEN");
        assertThat(captor.getValue().getHandleUserId()).isNull();
        assertThat(captor.getValue().getHandleRemark()).isNull();
    }

    @Test
    void transit_illegalJump_returns400() throws Exception {
        loginAsProjectAdmin(7L, 5L);
        when(auditResultMapper.selectById(34L)).thenReturn(result(34L, 5L, "OPEN"));

        mockMvc.perform(put("/api/projects/5/issues/34/status")
                        .principal(SecurityContextHolder.getContext().getAuthentication())
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("不允许的状态流转: OPEN → RESOLVED"));
    }

    @Test
    void transit_notProjectAdmin_returns400() throws Exception {
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(new LoginUser("user", 8L), "password");
        SecurityContextHolder.getContext().setAuthentication(token);
        when(projectService.isAdmin(5L, 8L)).thenReturn(false);

        mockMvc.perform(put("/api/projects/5/issues/30/status")
                        .principal(token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("该操作需要项目管理员权限"));
    }
}
