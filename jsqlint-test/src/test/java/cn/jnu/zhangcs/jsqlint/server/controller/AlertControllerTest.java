package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.entity.AlertInstance;
import cn.jnu.zhangcs.jsqlint.server.entity.AlertRule;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.model.dto.AlertRuleDTO;
import cn.jnu.zhangcs.jsqlint.server.model.dto.AlertRuleUpdateDTO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.AlertService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AlertController} 单元测试（standalone MockMvc，service 全部 Mock）。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class AlertControllerTest {

    @Mock
    private AlertService alertService;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private AlertController alertController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(alertController)
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
    void listRules_withProject_returnsVoWithDescriptions() throws Exception {
        AlertRule rule = buildRule(9L, "高危问题过多", "AUDIT_ISSUE_COUNT", 5L, "high", "warning", 1);
        when(alertService.getRulePage(5L, "AUDIT_ISSUE_COUNT", true, 1, 10)).thenReturn(pageOf(List.of(rule), 1));
        when(projectService.getById(5L)).thenReturn(buildProject(5L, "订单系统"));

        mockMvc.perform(get("/api/alerts/rules")
                        .param("projectId", "5")
                        .param("ruleType", "AUDIT_ISSUE_COUNT")
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list.length()").value(1))
                .andExpect(jsonPath("$.list[0].id").value(9))
                .andExpect(jsonPath("$.list[0].name").value("高危问题过多"))
                .andExpect(jsonPath("$.list[0].ruleTypeDescription").value("审核问题数"))
                .andExpect(jsonPath("$.list[0].projectName").value("订单系统"))
                .andExpect(jsonPath("$.list[0].severityDescription").value("高危"))
                .andExpect(jsonPath("$.list[0].minSeverityDescription").value("警告"))
                .andExpect(jsonPath("$.list[0].thresholdCount").value(3))
                // Integer(0/1) 转换为 Boolean
                .andExpect(jsonPath("$.list[0].notifyProjectMembers").value(true))
                .andExpect(jsonPath("$.list[0].notifyCreator").value(false))
                .andExpect(jsonPath("$.list[0].enabled").value(1))
                .andExpect(jsonPath("$.list[0].enabledFlag").value(true));
    }

    @Test
    void listRules_globalRule_showsAllProjects() throws Exception {
        AlertRule rule = buildRule(9L, "任务失败告警", "TASK_FAILED", null, "critical", "high", 1);
        when(alertService.getRulePage(null, null, null, 1, 10)).thenReturn(pageOf(List.of(rule), 1));

        mockMvc.perform(get("/api/alerts/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].projectName").value("全部项目"))
                .andExpect(jsonPath("$.list[0].ruleTypeDescription").value("任务失败"))
                .andExpect(jsonPath("$.list[0].severityDescription").value("致命"))
                .andExpect(jsonPath("$.list[0].minSeverityDescription").value("高危"));
    }

    @Test
    void createRule_validBody_returnsVo() throws Exception {
        AlertRule created = buildRule(9L, "任务失败告警", "TASK_FAILED", null, "critical", "high", 1);
        when(alertService.addRule(any(AlertRuleDTO.class), eq(7L))).thenReturn(created);

        mockMvc.perform(post("/api/alerts/rules")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"任务失败告警\",\"ruleType\":\"TASK_FAILED\","
                                + "\"severity\":\"critical\",\"minSeverity\":\"high\",\"thresholdCount\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.ruleTypeDescription").value("任务失败"))
                .andExpect(jsonPath("$.severityDescription").value("致命"))
                .andExpect(jsonPath("$.enabledFlag").value(true));

        ArgumentCaptor<AlertRuleDTO> captor = ArgumentCaptor.forClass(AlertRuleDTO.class);
        verify(alertService).addRule(captor.capture(), eq(7L));
        assertEquals("任务失败告警", captor.getValue().getName());
        assertEquals("TASK_FAILED", captor.getValue().getRuleType());
        assertEquals("critical", captor.getValue().getSeverity());
        assertEquals(2, captor.getValue().getThresholdCount());
    }

    @Test
    void createRule_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/alerts/rules")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"ruleType\":\"TASK_FAILED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数校验失败"))
                .andExpect(jsonPath("$.errors.name").value("告警规则名称不能为空"));
    }

    @Test
    void createRule_invalidThreshold_returns400() throws Exception {
        mockMvc.perform(post("/api/alerts/rules")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"任务失败告警\",\"ruleType\":\"TASK_FAILED\",\"thresholdCount\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数校验失败"))
                .andExpect(jsonPath("$.errors.thresholdCount").value("触发阈值必须大于0"));
    }

    @Test
    void updateRule_validBody_returnsVo() throws Exception {
        AlertRule updated = buildRule(9L, "新规则名", "AUDIT_ISSUE_COUNT", 5L, "high", "warning", 1);
        updated.setThresholdCount(5);
        when(alertService.updateRule(eq(9L), any(AlertRuleUpdateDTO.class))).thenReturn(updated);
        when(projectService.getById(5L)).thenReturn(buildProject(5L, "订单系统"));

        mockMvc.perform(put("/api/alerts/rules/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"新规则名\",\"thresholdCount\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("新规则名"))
                .andExpect(jsonPath("$.thresholdCount").value(5))
                .andExpect(jsonPath("$.projectName").value("订单系统"));

        ArgumentCaptor<AlertRuleUpdateDTO> captor = ArgumentCaptor.forClass(AlertRuleUpdateDTO.class);
        verify(alertService).updateRule(eq(9L), captor.capture());
        assertEquals("新规则名", captor.getValue().getName());
        assertEquals(5, captor.getValue().getThresholdCount());
    }

    @Test
    void updateRule_invalidThreshold_returns400() throws Exception {
        mockMvc.perform(put("/api/alerts/rules/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"thresholdCount\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数校验失败"))
                .andExpect(jsonPath("$.errors.thresholdCount").value("触发阈值必须大于0"));
    }

    @Test
    void deleteRule_returnsSuccessMessage() throws Exception {
        mockMvc.perform(delete("/api/alerts/rules/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("删除成功"));

        verify(alertService).deleteRule(9L);
    }

    @Test
    void toggleRule_enabledTrue_returnsVo() throws Exception {
        AlertRule updated = buildRule(9L, "高危问题过多", "AUDIT_ISSUE_COUNT", null, "high", "warning", 1);
        when(alertService.toggleRule(9L, true)).thenReturn(updated);

        mockMvc.perform(put("/api/alerts/rules/9/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabledFlag").value(true));
    }

    @Test
    void toggleRule_missingFlag_defaultsToFalse() throws Exception {
        AlertRule updated = buildRule(9L, "高危问题过多", "AUDIT_ISSUE_COUNT", null, "high", "warning", 0);
        when(alertService.toggleRule(9L, false)).thenReturn(updated);

        mockMvc.perform(put("/api/alerts/rules/9/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabledFlag").value(false));

        verify(alertService).toggleRule(9L, false);
    }

    @Test
    void listInstances_returnsVoWithRuleAndProjectNames() throws Exception {
        AlertInstance instance = buildInstance(21L, 9L, 5L, "high", "OPEN");
        when(alertService.getInstancePage(5L, "high", "OPEN", 1, 10)).thenReturn(pageOf(List.of(instance), 1));
        when(alertService.getRuleById(9L))
                .thenReturn(buildRule(9L, "高危审核规则", "AUDIT_ISSUE_COUNT", 5L, "high", "warning", 1));
        when(projectService.getById(5L)).thenReturn(buildProject(5L, "订单系统"));

        mockMvc.perform(get("/api/alerts")
                        .param("projectId", "5")
                        .param("severity", "high")
                        .param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list.length()").value(1))
                .andExpect(jsonPath("$.list[0].id").value(21))
                .andExpect(jsonPath("$.list[0].ruleName").value("高危审核规则"))
                .andExpect(jsonPath("$.list[0].projectName").value("订单系统"))
                .andExpect(jsonPath("$.list[0].title").value("高危问题过多"))
                .andExpect(jsonPath("$.list[0].severityDescription").value("高危"))
                .andExpect(jsonPath("$.list[0].statusDescription").value("未处理"))
                .andExpect(jsonPath("$.list[0].triggerCount").value(2));
    }

    @Test
    void listInstances_withoutRuleAndProject_leavesNamesNull() throws Exception {
        AlertInstance instance = buildInstance(21L, null, null, "info", "RESOLVED");
        when(alertService.getInstancePage(null, "info", "RESOLVED", 1, 10)).thenReturn(pageOf(List.of(instance), 1));

        mockMvc.perform(get("/api/alerts")
                        .param("severity", "info")
                        .param("status", "RESOLVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].id").value(21))
                .andExpect(jsonPath("$.list[0].ruleName").value(nullValue()))
                .andExpect(jsonPath("$.list[0].projectName").value(nullValue()))
                .andExpect(jsonPath("$.list[0].severityDescription").value("信息"))
                .andExpect(jsonPath("$.list[0].statusDescription").value("已解决"));
    }

    @Test
    void updateStatus_validBody_returnsSuccessMessage() throws Exception {
        mockMvc.perform(put("/api/alerts/12/status")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"status\":\"ACKED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("更新成功"));

        verify(alertService).updateInstanceStatus(12L, "ACKED", 7L);
    }

    @Test
    void updateStatus_bizExceptionMappedTo400() throws Exception {
        doThrow(new BizException("告警实例不存在")).when(alertService).updateInstanceStatus(12L, "ACKED", 7L);

        mockMvc.perform(put("/api/alerts/12/status")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"status\":\"ACKED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("告警实例不存在"));
    }

    /**
     * 构造分页结果。
     */
    private <T> com.baomidou.mybatisplus.extension.plugins.pagination.Page<T> pageOf(List<T> records, long total) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<T> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
        page.setRecords(records);
        page.setTotal(total);
        return page;
    }

    /**
     * 构造一个最小可断言的告警规则实体。
     */
    private AlertRule buildRule(Long id, String name, String ruleType, Long projectId,
                                String severity, String minSeverity, Integer enabled) {
        AlertRule rule = new AlertRule();
        rule.setId(id);
        rule.setName(name);
        rule.setRuleType(ruleType);
        rule.setProjectId(projectId);
        rule.setSeverity(severity);
        rule.setMinSeverity(minSeverity);
        rule.setThresholdCount(3);
        rule.setNotifyProjectMembers(1);
        rule.setNotifyCreator(0);
        rule.setEnabled(enabled);
        return rule;
    }

    /**
     * 构造一个最小可断言的告警实例实体。
     */
    private AlertInstance buildInstance(Long id, Long ruleId, Long projectId, String severity, String status) {
        AlertInstance instance = new AlertInstance();
        instance.setId(id);
        instance.setRuleId(ruleId);
        instance.setProjectId(projectId);
        instance.setVersionId(3L);
        instance.setTaskId(8L);
        instance.setTitle("高危问题过多");
        instance.setContent("发现 5 个高危问题");
        instance.setSeverity(severity);
        instance.setStatus(status);
        instance.setTriggerCount(2);
        return instance;
    }

    private Project buildProject(Long id, String name) {
        Project project = new Project();
        project.setId(id);
        project.setName(name);
        return project;
    }
}
