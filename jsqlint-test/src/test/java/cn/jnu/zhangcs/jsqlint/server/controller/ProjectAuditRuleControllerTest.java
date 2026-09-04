package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditRule;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.model.dto.ProjectAuditRuleDTO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectAuditRuleService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ProjectAuditRuleController} 单元测试（standalone MockMvc，service 全部 Mock）。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class ProjectAuditRuleControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private ProjectAuditRuleService projectAuditRuleService;

    @Mock
    private cn.jnu.zhangcs.jsqlint.server.service.ProjectService projectService;

    @InjectMocks
    private ProjectAuditRuleController projectAuditRuleController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(projectAuditRuleController)
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
        AuditRule rule = buildProjectRule(9L, 5L, "禁止 SELECT *", "NO_SELECT_STAR", 7L);
        when(projectAuditRuleService.listRules(5L, 1, 10, null, null, null)).thenReturn(List.of(rule));
        when(projectAuditRuleService.countRules(5L, null, null, null)).thenReturn(3L);
        when(userService.getById(7L)).thenReturn(buildUser(7L, "admin"));

        mockMvc.perform(get("/api/projects/5/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.list.length()").value(1))
                .andExpect(jsonPath("$.list[0].id").value(9))
                .andExpect(jsonPath("$.list[0].projectId").value(5))
                .andExpect(jsonPath("$.list[0].code").value("NO_SELECT_STAR"))
                .andExpect(jsonPath("$.list[0].createUserName").value("admin"))
                .andExpect(jsonPath("$.list[0].severityDescription").value("高危"))
                .andExpect(jsonPath("$.list[0].categoryDescription").value("性能"))
                .andExpect(jsonPath("$.list[0].enabled").value(1));
    }

    @Test
    void listRules_withFilters_returnsEmptyList() throws Exception {
        when(projectAuditRuleService.listRules(5L, 2, 5, "注入", "PERFORMANCE", true)).thenReturn(List.of());
        when(projectAuditRuleService.countRules(5L, "注入", "PERFORMANCE", true)).thenReturn(0L);

        mockMvc.perform(get("/api/projects/5/rules")
                        .param("page", "2")
                        .param("pageSize", "5")
                        .param("name", "注入")
                        .param("category", "PERFORMANCE")
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.list.length()").value(0));
    }

    @Test
    void getRule_existing_returnsVo() throws Exception {
        when(projectAuditRuleService.getRule(5L, 9L))
                .thenReturn(buildProjectRule(9L, 5L, "禁止 SELECT *", "NO_SELECT_STAR", 7L));
        when(userService.getById(7L)).thenReturn(buildUser(7L, "admin"));

        mockMvc.perform(get("/api/projects/5/rules/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.projectId").value(5))
                .andExpect(jsonPath("$.name").value("禁止 SELECT *"))
                .andExpect(jsonPath("$.code").value("NO_SELECT_STAR"))
                .andExpect(jsonPath("$.createUserName").value("admin"));
    }

    @Test
    void getRule_missingRule_returns404() throws Exception {
        when(projectAuditRuleService.getRule(5L, 99L)).thenReturn(null);

        mockMvc.perform(get("/api/projects/5/rules/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createRule_validBody_returnsVo() throws Exception {
        AuditRule created = buildProjectRule(9L, 5L, "禁止 SELECT *", "NO_SELECT_STAR", 7L);
        when(projectService.isAdmin(5L, 7L)).thenReturn(true);
        when(projectAuditRuleService.createRule(eq(5L), any(ProjectAuditRuleDTO.class), eq(7L))).thenReturn(created);
        when(userService.getById(7L)).thenReturn(buildUser(7L, "admin"));

        mockMvc.perform(post("/api/projects/5/rules")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"禁止 SELECT *\",\"code\":\"NO_SELECT_STAR\","
                                + "\"severity\":\"high\",\"category\":\"PERFORMANCE\","
                                + "\"droolsContent\":\"rule no_select_star when $s : Sql() then end\","
                                + "\"suggestion\":\"明确列名\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.projectId").value(5))
                .andExpect(jsonPath("$.code").value("NO_SELECT_STAR"))
                .andExpect(jsonPath("$.createUserName").value("admin"))
                .andExpect(jsonPath("$.enabled").value(1));

        ArgumentCaptor<ProjectAuditRuleDTO> captor = ArgumentCaptor.forClass(ProjectAuditRuleDTO.class);
        verify(projectAuditRuleService).createRule(eq(5L), captor.capture(), eq(7L));
        assertEquals("禁止 SELECT *", captor.getValue().getName());
        assertEquals("NO_SELECT_STAR", captor.getValue().getCode());
        assertEquals("high", captor.getValue().getSeverity());
        assertEquals(Boolean.TRUE, captor.getValue().getEnabled());
    }

    @Test
    void createRule_invalidSeverity_returns400() throws Exception {
        mockMvc.perform(post("/api/projects/5/rules")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"禁止 SELECT *\",\"code\":\"NO_SELECT_STAR\",\"severity\":\"fatal\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数校验失败"))
                .andExpect(jsonPath("$.errors.severity").value("严重级别必须是 critical/high/warning/info 之一"));
    }

    @Test
    void createRule_bizExceptionMappedTo400() throws Exception {
        when(projectService.isAdmin(5L, 7L)).thenReturn(true);
        when(projectAuditRuleService.createRule(eq(5L), any(ProjectAuditRuleDTO.class), eq(7L)))
                .thenThrow(new BizException("规则代码已存在"));

        mockMvc.perform(post("/api/projects/5/rules")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"禁止 SELECT *\",\"code\":\"NO_SELECT_STAR\","
                                + "\"severity\":\"high\",\"category\":\"PERFORMANCE\","
                                + "\"droolsContent\":\"rule no_select_star when $s : Sql() then end\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("规则代码已存在"));
    }

    @Test
    void updateRule_validBody_returnsVo() throws Exception {
        AuditRule updated = buildProjectRule(9L, 5L, "禁止 SELECT *（更新）", "NO_SELECT_STAR", 7L);
        updated.setSuggestion("明确列名");
        when(projectService.isAdmin(5L, 7L)).thenReturn(true);
        when(projectAuditRuleService.updateRule(eq(5L), eq(9L), any(ProjectAuditRuleDTO.class), eq(7L)))
                .thenReturn(updated);
        when(userService.getById(7L)).thenReturn(buildUser(7L, "admin"));

        mockMvc.perform(put("/api/projects/5/rules/9")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"禁止 SELECT *（更新）\",\"suggestion\":\"明确列名\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("禁止 SELECT *（更新）"))
                .andExpect(jsonPath("$.suggestion").value("明确列名"))
                .andExpect(jsonPath("$.createUserName").value("admin"));

        ArgumentCaptor<ProjectAuditRuleDTO> captor = ArgumentCaptor.forClass(ProjectAuditRuleDTO.class);
        verify(projectAuditRuleService).updateRule(eq(5L), eq(9L), captor.capture(), eq(7L));
        assertEquals("禁止 SELECT *（更新）", captor.getValue().getName());
    }

    @Test
    void updateRule_invalidCodePattern_returns400() throws Exception {
        mockMvc.perform(put("/api/projects/5/rules/9")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"code\":\"bad-code\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数校验失败"))
                .andExpect(jsonPath("$.errors.code").value("规则代码必须是大写字母、数字和下划线组成"));
    }

    @Test
    void deleteRule_existing_returns200() throws Exception {
        login(7L);
        when(projectService.isAdmin(5L, 7L)).thenReturn(true);

        mockMvc.perform(delete("/api/projects/5/rules/9")
                        .principal(login(7L)))
                .andExpect(status().isOk());

        verify(projectAuditRuleService).deleteRule(5L, 9L);
    }

    @Test
    void toggleRule_enabledTrue_returnsVo() throws Exception {
        AuditRule updated = buildProjectRule(9L, 5L, "禁止 SELECT *", "NO_SELECT_STAR", 7L);
        when(projectService.isAdmin(5L, 7L)).thenReturn(true);
        when(projectAuditRuleService.toggleRule(5L, 9L, true, 7L)).thenReturn(updated);
        when(userService.getById(7L)).thenReturn(buildUser(7L, "admin"));

        mockMvc.perform(put("/api/projects/5/rules/9/toggle")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(1))
                .andExpect(jsonPath("$.createUserName").value("admin"));

        verify(projectAuditRuleService).toggleRule(5L, 9L, true, 7L);
    }

    @Test
    void toggleRule_missingFlag_defaultsToFalse() throws Exception {
        AuditRule updated = buildProjectRule(9L, 5L, "禁止 SELECT *", "NO_SELECT_STAR", null);
        updated.setEnabled(0);
        when(projectService.isAdmin(5L, 7L)).thenReturn(true);
        when(projectAuditRuleService.toggleRule(5L, 9L, false, 7L)).thenReturn(updated);

        mockMvc.perform(put("/api/projects/5/rules/9/toggle")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(0))
                .andExpect(jsonPath("$.createUserName").value(nullValue()));

        verify(projectAuditRuleService).toggleRule(5L, 9L, false, 7L);
    }

    /**
     * 构造一个最小可断言的项目级审核规则实体。
     */
    private AuditRule buildProjectRule(Long id, Long projectId, String name, String code, Long createUserId) {
        AuditRule rule = new AuditRule();
        rule.setId(id);
        rule.setName(name);
        rule.setCode(code);
        rule.setDescription("规则描述");
        rule.setSeverity("high");
        rule.setSeverityDescription("高危");
        rule.setCategory("PERFORMANCE");
        rule.setCategoryDescription("性能");
        rule.setProjectId(projectId);
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
