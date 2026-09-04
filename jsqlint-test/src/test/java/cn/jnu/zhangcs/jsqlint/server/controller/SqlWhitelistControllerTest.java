package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditRule;
import cn.jnu.zhangcs.jsqlint.server.entity.SqlWhitelist;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditRuleMapper;
import cn.jnu.zhangcs.jsqlint.server.model.dto.SqlWhitelistDTO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.SqlWhitelistService;
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
import java.time.LocalDateTime;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SqlWhitelistController} 单元测试（standalone MockMvc，service/mapper 全部 Mock）。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class SqlWhitelistControllerTest {

    @Mock
    private SqlWhitelistService sqlWhitelistService;

    @Mock
    private AuditRuleMapper auditRuleMapper;

    @Mock
    private UserService userService;

    @Mock
    private cn.jnu.zhangcs.jsqlint.server.service.ProjectService projectService;

    @InjectMocks
    private SqlWhitelistController sqlWhitelistController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(sqlWhitelistController)
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
    void list_returnsPageWithRuleAndUserNames() throws Exception {
        SqlWhitelist item = buildWhitelist(12L, 5L, 3L, 7L);
        when(sqlWhitelistService.list(5L, 1, 10, null)).thenReturn(List.of(item));
        when(sqlWhitelistService.count(5L, null)).thenReturn(1L);
        when(auditRuleMapper.selectById(3L)).thenReturn(buildRule(3L, "禁止无 WHERE 删除"));
        when(userService.getById(7L)).thenReturn(buildUser(7L, "admin"));

        mockMvc.perform(get("/api/projects/5/whitelist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list.length()").value(1))
                .andExpect(jsonPath("$.list[0].id").value(12))
                .andExpect(jsonPath("$.list[0].projectId").value(5))
                .andExpect(jsonPath("$.list[0].sqlSample").value("delete from t_order where id = 1"))
                .andExpect(jsonPath("$.list[0].ruleId").value(3))
                .andExpect(jsonPath("$.list[0].ruleName").value("禁止无 WHERE 删除"))
                .andExpect(jsonPath("$.list[0].reason").value("误报加白"))
                .andExpect(jsonPath("$.list[0].createUserName").value("admin"));
    }

    @Test
    void list_withRuleIdFilter_returnsEmptyPage() throws Exception {
        when(sqlWhitelistService.list(5L, 2, 5, 9L)).thenReturn(List.of());
        when(sqlWhitelistService.count(5L, 9L)).thenReturn(0L);

        mockMvc.perform(get("/api/projects/5/whitelist")
                        .param("page", "2")
                        .param("pageSize", "5")
                        .param("ruleId", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.list.length()").value(0));
    }

    @Test
    void list_missingRuleAndUser_leavesNamesNull() throws Exception {
        SqlWhitelist item = buildWhitelist(12L, 5L, null, null);
        when(sqlWhitelistService.list(5L, 1, 10, null)).thenReturn(List.of(item));
        when(sqlWhitelistService.count(5L, null)).thenReturn(1L);

        mockMvc.perform(get("/api/projects/5/whitelist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].id").value(12))
                .andExpect(jsonPath("$.list[0].ruleName").value(nullValue()))
                .andExpect(jsonPath("$.list[0].createUserName").value(nullValue()));
    }

    @Test
    void create_validBody_returnsVoWithNames() throws Exception {
        SqlWhitelist created = buildWhitelist(12L, 5L, 3L, 7L);
        when(projectService.isAdmin(5L, 7L)).thenReturn(true);
        when(sqlWhitelistService.create(eq(5L), any(SqlWhitelistDTO.class), eq(7L))).thenReturn(created);
        when(auditRuleMapper.selectById(3L)).thenReturn(buildRule(3L, "禁止无 WHERE 删除"));
        when(userService.getById(7L)).thenReturn(buildUser(7L, "admin"));

        mockMvc.perform(post("/api/projects/5/whitelist")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"sql\":\"delete from t_order where id = 1\",\"ruleId\":3,\"reason\":\"误报加白\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.projectId").value(5))
                .andExpect(jsonPath("$.ruleId").value(3))
                .andExpect(jsonPath("$.ruleName").value("禁止无 WHERE 删除"))
                .andExpect(jsonPath("$.createUserName").value("admin"));

        ArgumentCaptor<SqlWhitelistDTO> captor = ArgumentCaptor.forClass(SqlWhitelistDTO.class);
        verify(sqlWhitelistService).create(eq(5L), captor.capture(), eq(7L));
        assertEquals("delete from t_order where id = 1", captor.getValue().getSql());
        assertEquals(3L, captor.getValue().getRuleId());
        assertEquals("误报加白", captor.getValue().getReason());
    }

    @Test
    void create_blankSql_returns400() throws Exception {
        mockMvc.perform(post("/api/projects/5/whitelist")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"sql\":\"\",\"ruleId\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数校验失败"))
                .andExpect(jsonPath("$.errors.sql").value("SQL 语句不能为空"));
    }

    @Test
    void create_missingRuleId_returns400() throws Exception {
        mockMvc.perform(post("/api/projects/5/whitelist")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"sql\":\"select 1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数校验失败"))
                .andExpect(jsonPath("$.errors.ruleId").value("规则ID不能为空"));
    }

    @Test
    void create_overlongReason_returns400() throws Exception {
        mockMvc.perform(post("/api/projects/5/whitelist")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"sql\":\"select 1\",\"ruleId\":3,\"reason\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数校验失败"))
                .andExpect(jsonPath("$.errors.reason").value("加白原因不能超过500字符"));
    }

    @Test
    void create_bizExceptionMappedTo400() throws Exception {
        when(projectService.isAdmin(5L, 7L)).thenReturn(true);
        when(sqlWhitelistService.create(eq(5L), any(SqlWhitelistDTO.class), eq(7L)))
                .thenThrow(new BizException("该 SQL 已在白名单中"));

        mockMvc.perform(post("/api/projects/5/whitelist")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"sql\":\"select 1\",\"ruleId\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("该 SQL 已在白名单中"));
    }

    @Test
    void delete_existing_returns200() throws Exception {
        login(7L);
        when(projectService.isAdmin(5L, 7L)).thenReturn(true);

        mockMvc.perform(delete("/api/projects/5/whitelist/12")
                        .principal(login(7L)))
                .andExpect(status().isOk());

        verify(sqlWhitelistService).delete(5L, 12L);
    }

    /**
     * 构造一个最小可断言的白名单实体。
     */
    private SqlWhitelist buildWhitelist(Long id, Long projectId, Long ruleId, Long createUserId) {
        SqlWhitelist whitelist = new SqlWhitelist();
        whitelist.setId(id);
        whitelist.setProjectId(projectId);
        whitelist.setSqlFingerprint("fingerprint-" + id);
        whitelist.setSqlSample("delete from t_order where id = 1");
        whitelist.setRuleId(ruleId);
        whitelist.setReason("误报加白");
        whitelist.setCreateUserId(createUserId);
        whitelist.setCreateTime(LocalDateTime.of(2026, 1, 1, 10, 0));
        return whitelist;
    }

    private AuditRule buildRule(Long id, String name) {
        AuditRule rule = new AuditRule();
        rule.setId(id);
        rule.setName(name);
        return rule;
    }

    private User buildUser(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }
}
