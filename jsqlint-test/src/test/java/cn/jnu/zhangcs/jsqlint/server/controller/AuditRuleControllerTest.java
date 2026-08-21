package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditRule;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.model.dto.AuditRuleDTO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.AuditRuleService;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AuditRuleController} 单元测试（standalone MockMvc，service 全部 Mock）。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class AuditRuleControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private AuditRuleService auditRuleService;

    @InjectMocks
    private AuditRuleController auditRuleController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(auditRuleController)
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

    @Test
    void listRules_returnsPageWithTotal() throws Exception {
        AuditRule rule = buildGlobalRule(9L, "禁止无 WHERE 删除", "NO_FULL_DELETE", 7L);
        when(auditRuleService.listRules(1, 10, null, null, null)).thenReturn(List.of(rule));
        when(auditRuleService.countRules(null, null, null)).thenReturn(5L);
        when(userService.getById(7L)).thenReturn(buildUser(7L, "admin"));

        mockMvc.perform(get("/api/audit-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.list.length()").value(1))
                .andExpect(jsonPath("$.list[0].id").value(9))
                .andExpect(jsonPath("$.list[0].code").value("NO_FULL_DELETE"))
                .andExpect(jsonPath("$.list[0].createUserName").value("admin"))
                .andExpect(jsonPath("$.list[0].severityDescription").value("高危"))
                .andExpect(jsonPath("$.list[0].categoryDescription").value("安全"))
                .andExpect(jsonPath("$.list[0].enabled").value(1));
    }

    @Test
    void listRules_withFilters_returnsEmptyList() throws Exception {
        when(auditRuleService.listRules(2, 5, "注入", "SECURITY", true)).thenReturn(List.of());
        when(auditRuleService.countRules("注入", "SECURITY", true)).thenReturn(0L);

        mockMvc.perform(get("/api/audit-rules")
                        .param("page", "2")
                        .param("pageSize", "5")
                        .param("name", "注入")
                        .param("category", "SECURITY")
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.list.length()").value(0));
    }

    @Test
    void getRule_globalRule_returnsVo() throws Exception {
        when(auditRuleService.getRuleById(9L)).thenReturn(buildGlobalRule(9L, "禁止无 WHERE 删除", "NO_FULL_DELETE", null));

        mockMvc.perform(get("/api/audit-rules/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.name").value("禁止无 WHERE 删除"))
                .andExpect(jsonPath("$.code").value("NO_FULL_DELETE"))
                .andExpect(jsonPath("$.enabled").value(1))
                .andExpect(jsonPath("$.createUserName").value(nullValue()));
    }

    @Test
    void getRule_projectScopedRule_returns404() throws Exception {
        AuditRule rule = buildGlobalRule(9L, "项目特有规则", "PROJECT_ONLY", null);
        rule.setProjectId(5L);
        when(auditRuleService.getRuleById(9L)).thenReturn(rule);

        mockMvc.perform(get("/api/audit-rules/9"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getRule_notFound_returns404() throws Exception {
        when(auditRuleService.getRuleById(99L)).thenReturn(null);

        mockMvc.perform(get("/api/audit-rules/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createRule_validBody_convertsDtoAndReturnsVo() throws Exception {
        AuditRule created = buildGlobalRule(9L, "禁止全表删除", "NO_FULL_DELETE", 7L);
        when(auditRuleService.createRule(any(AuditRule.class), eq(7L))).thenReturn(created);
        when(userService.getById(7L)).thenReturn(buildUser(7L, "admin"));

        mockMvc.perform(post("/api/audit-rules")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"禁止全表删除\",\"code\":\"NO_FULL_DELETE\","
                                + "\"description\":\"禁止不带 WHERE 的 DELETE\","
                                + "\"severity\":\"critical\",\"category\":\"SECURITY\","
                                + "\"droolsContent\":\"rule no_full_delete when $s : Sql() then end\","
                                + "\"suggestion\":\"补充 WHERE 条件\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.code").value("NO_FULL_DELETE"))
                .andExpect(jsonPath("$.createUserName").value("admin"));

        ArgumentCaptor<AuditRule> captor = ArgumentCaptor.forClass(AuditRule.class);
        verify(auditRuleService).createRule(captor.capture(), eq(7L));
        assertEquals("禁止全表删除", captor.getValue().getName());
        assertEquals("NO_FULL_DELETE", captor.getValue().getCode());
        assertEquals("critical", captor.getValue().getSeverity());
        assertEquals("SECURITY", captor.getValue().getCategory());
        assertEquals("rule no_full_delete when $s : Sql() then end", captor.getValue().getDroolsContent());
    }

    @Test
    void createRule_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/audit-rules")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"\",\"code\":\"NO_FULL_DELETE\",\"severity\":\"warning\","
                                + "\"category\":\"SQL_QUALITY\",\"droolsContent\":\"rule r\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数校验失败"))
                .andExpect(jsonPath("$.errors.name").value("规则名称不能为空"));
    }

    @Test
    void createRule_invalidCodePattern_returns400() throws Exception {
        mockMvc.perform(post("/api/audit-rules")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"禁止全表删除\",\"code\":\"bad-code\",\"severity\":\"warning\","
                                + "\"category\":\"SQL_QUALITY\",\"droolsContent\":\"rule r\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数校验失败"))
                .andExpect(jsonPath("$.errors.code").value("规则代码必须是大写字母、数字和下划线组成"));
    }

    @Test
    void updateRule_partialBody_mergesWithExisting() throws Exception {
        AuditRule existing = buildGlobalRule(9L, "旧名称", "NO_FULL_DELETE", null);
        existing.setSeverity("warning");
        existing.setCategory("SQL_QUALITY");
        existing.setDroolsContent("old content");
        existing.setSuggestion("旧建议");
        when(auditRuleService.getRuleById(9L)).thenReturn(existing);

        AuditRule updated = buildGlobalRule(9L, "新名称", "NO_FULL_DELETE", null);
        when(auditRuleService.updateRule(eq(9L), any(AuditRule.class))).thenReturn(updated);

        mockMvc.perform(put("/api/audit-rules/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"新名称\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("新名称"))
                .andExpect(jsonPath("$.code").value("NO_FULL_DELETE"));

        ArgumentCaptor<AuditRule> captor = ArgumentCaptor.forClass(AuditRule.class);
        verify(auditRuleService).updateRule(eq(9L), captor.capture());
        // 未传字段回退到已有规则，code 保持不变；description 直接取 dto 值（未传即清空，属预期行为）
        assertEquals("新名称", captor.getValue().getName());
        assertEquals("NO_FULL_DELETE", captor.getValue().getCode());
        assertNull(captor.getValue().getDescription());
        assertEquals("warning", captor.getValue().getSeverity());
        assertEquals("SQL_QUALITY", captor.getValue().getCategory());
        assertEquals("old content", captor.getValue().getDroolsContent());
    }

    @Test
    void updateRule_notFound_returns404() throws Exception {
        when(auditRuleService.getRuleById(9L)).thenReturn(null);

        mockMvc.perform(put("/api/audit-rules/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"新名称\"}"))
                .andExpect(status().isNotFound());

        verify(auditRuleService, never()).updateRule(anyLong(), any());
    }

    @Test
    void toggleRule_enabled_returnsUpdatedVo() throws Exception {
        AuditRule existing = buildGlobalRule(9L, "禁止全表删除", "NO_FULL_DELETE", null);
        AuditRule updated = buildGlobalRule(9L, "禁止全表删除", "NO_FULL_DELETE", null);
        when(auditRuleService.getRuleById(9L)).thenReturn(existing, updated);

        mockMvc.perform(put("/api/audit-rules/9/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(1));

        verify(auditRuleService).toggleRule(9L, true);
    }

    @Test
    void deleteRule_globalRule_returns200() throws Exception {
        when(auditRuleService.getRuleById(9L)).thenReturn(buildGlobalRule(9L, "禁止全表删除", "NO_FULL_DELETE", null));

        mockMvc.perform(delete("/api/audit-rules/9"))
                .andExpect(status().isOk());

        verify(auditRuleService).deleteRule(9L);
    }

    @Test
    void deleteRule_notFound_returns404() throws Exception {
        when(auditRuleService.getRuleById(99L)).thenReturn(null);

        mockMvc.perform(delete("/api/audit-rules/99"))
                .andExpect(status().isNotFound());

        verify(auditRuleService, never()).deleteRule(anyLong());
    }

    @Test
    void validateRule_validContent_returnsTrue() throws Exception {
        when(auditRuleService.validateRule("rule no_full_delete when $s : Sql() then end")).thenReturn(true);

        mockMvc.perform(post("/api/audit-rules/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"ruleContent\":\"rule no_full_delete when $s : Sql() then end\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void validateRule_invalidContent_returnsFalse() throws Exception {
        when(auditRuleService.validateRule("bad drl")).thenReturn(false);

        mockMvc.perform(post("/api/audit-rules/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"ruleContent\":\"bad drl\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    void reloadRules_returnsSuccess() throws Exception {
        mockMvc.perform(post("/api/audit-rules/reload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("启用规则校验成功"));

        verify(auditRuleService).reloadRules();
    }

    @Test
    void createRule_bizExceptionMappedTo400() throws Exception {
        when(auditRuleService.createRule(any(AuditRule.class), eq(7L)))
                .thenThrow(new BizException("规则代码已存在"));

        mockMvc.perform(post("/api/audit-rules")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"禁止全表删除\",\"code\":\"NO_FULL_DELETE\","
                                + "\"severity\":\"critical\",\"category\":\"SECURITY\","
                                + "\"droolsContent\":\"rule no_full_delete when $s : Sql() then end\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("规则代码已存在"));
    }

    /**
     * 构造一个最小可断言的全局审核规则实体。
     */
    private AuditRule buildGlobalRule(Long id, String name, String code, Long createUserId) {
        AuditRule rule = new AuditRule();
        rule.setId(id);
        rule.setName(name);
        rule.setCode(code);
        rule.setDescription("规则描述");
        rule.setSeverity("critical");
        rule.setSeverityDescription("高危");
        rule.setCategory("SECURITY");
        rule.setCategoryDescription("安全");
        rule.setDroolsContent("rule r when $s : Sql() then end");
        rule.setSuggestion("修复建议");
        rule.setEnabled(1);
        rule.setCreateUserId(createUserId);
        return rule;
    }

    private User buildUser(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }
}
