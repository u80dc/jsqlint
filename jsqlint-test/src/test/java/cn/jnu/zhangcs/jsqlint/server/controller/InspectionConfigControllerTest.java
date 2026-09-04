package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.entity.InspectionConfig;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.InspectionConfigService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link InspectionConfigController} 单元测试（standalone MockMvc，service 全部 Mock）。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class InspectionConfigControllerTest {

    @Mock
    private InspectionConfigService inspectionConfigService;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private InspectionConfigController inspectionConfigController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(inspectionConfigController)
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

    /** 写操作要求项目管理员，默认按管理员放行。 */
    private void loginAsProjectAdmin(Long userId, Long projectId) {
        login(userId);
        when(projectService.isAdmin(projectId, userId)).thenReturn(true);
    }

    @Test
    void list_withoutFilter_returnsConvertedVoList() throws Exception {
        InspectionConfig enabled = buildConfig(1L, 5L, "订单系统", "0 0 * * * ?", 1);
        InspectionConfig disabled = buildConfig(2L, 6L, "风控系统", "0 0 1 * * ?", 0);
        when(inspectionConfigService.list(null)).thenReturn(List.of(enabled, disabled));

        mockMvc.perform(get("/api/inspection-configs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].projectName").value("订单系统"))
                .andExpect(jsonPath("$[0].cronExpr").value("0 0 * * * ?"))
                // Integer(0/1) 转换为 Boolean
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[1].enabled").value(false))
                .andExpect(jsonPath("$[0].lastStatus").value("SUCCESS"))
                .andExpect(jsonPath("$[0].lastMessage").value("巡检完成"));
    }

    @Test
    void list_enabledFilterPassedToService() throws Exception {
        when(inspectionConfigService.list(true)).thenReturn(List.of());

        mockMvc.perform(get("/api/inspection-configs").param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getByProject_existing_returnsVo() throws Exception {
        when(inspectionConfigService.getByProjectId(5L)).thenReturn(buildConfig(1L, 5L, "订单系统", "0 0 * * * ?", 1));

        mockMvc.perform(get("/api/inspection-configs/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.projectId").value(5))
                .andExpect(jsonPath("$.projectName").value("订单系统"))
                .andExpect(jsonPath("$.cronExpr").value("0 0 * * * ?"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.nextExecTime").value(1735689600000L))
                .andExpect(jsonPath("$.operatorId").value(9));
    }

    @Test
    void getByProject_notFound_returns404() throws Exception {
        when(inspectionConfigService.getByProjectId(5L)).thenReturn(null);

        mockMvc.perform(get("/api/inspection-configs/5"))
                .andExpect(status().isNotFound());
    }

    @Test
    void saveOrUpdate_validBody_returnsSavedVoWithOperatorFromPrincipal() throws Exception {
        InspectionConfig saved = buildConfig(1L, 5L, "订单系统", "0 0 * * * ?", 1);
        when(projectService.isAdmin(5L, 7L)).thenReturn(true);
        when(inspectionConfigService.saveOrUpdate(5L, "0 0 * * * ?", true, 7L)).thenReturn(saved);

        mockMvc.perform(put("/api/inspection-configs/5")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"cronExpr\":\"0 0 * * * ?\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(5))
                .andExpect(jsonPath("$.cronExpr").value("0 0 * * * ?"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void saveOrUpdate_blankCronExpr_returns400() throws Exception {
        mockMvc.perform(put("/api/inspection-configs/5")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"cronExpr\":\"\",\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数校验失败"))
                .andExpect(jsonPath("$.errors.cronExpr").value("执行调度规则不能为空"));
    }

    @Test
    void saveOrUpdate_bizExceptionMappedTo400() throws Exception {
        when(projectService.isAdmin(5L, 7L)).thenReturn(true);
        when(inspectionConfigService.saveOrUpdate(eq(5L), eq("bad"), isNull(), eq(7L)))
                .thenThrow(new BizException("Cron 表达式不合法"));

        mockMvc.perform(put("/api/inspection-configs/5")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"cronExpr\":\"bad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cron 表达式不合法"));
    }

    @Test
    void setEnabled_true_returns204() throws Exception {
        loginAsProjectAdmin(7L, 5L);
        mockMvc.perform(patch("/api/inspection-configs/5/enabled")
                        .param("enabled", "true")
                        .principal(login(7L)))
                .andExpect(status().isNoContent());

        verify(inspectionConfigService).setEnabled(5L, true, 7L);
    }

    @Test
    void setEnabled_false_returns204() throws Exception {
        loginAsProjectAdmin(7L, 5L);
        mockMvc.perform(patch("/api/inspection-configs/5/enabled")
                        .param("enabled", "false")
                        .principal(login(7L)))
                .andExpect(status().isNoContent());

        verify(inspectionConfigService).setEnabled(5L, false, 7L);
    }

    @Test
    void delete_existing_returns204() throws Exception {
        loginAsProjectAdmin(7L, 5L);
        mockMvc.perform(delete("/api/inspection-configs/5")
                        .principal(login(7L)))
                .andExpect(status().isNoContent());

        verify(inspectionConfigService).deleteByProjectId(5L, 7L);
    }

    /**
     * 构造一个最小可断言的巡检配置实体。
     */
    private InspectionConfig buildConfig(Long id, Long projectId, String projectName, String cronExpr, int enabled) {
        InspectionConfig config = new InspectionConfig();
        config.setId(id);
        config.setProjectId(projectId);
        config.setProjectName(projectName);
        config.setCronExpr(cronExpr);
        config.setEnabled(enabled);
        config.setNextExecTime(1735689600000L);
        config.setLastExecTime(1735603200000L);
        config.setLastStatus("SUCCESS");
        config.setLastMessage("巡检完成");
        config.setOperatorId(9L);
        config.setCreateTime(LocalDateTime.of(2026, 1, 1, 8, 0));
        config.setUpdateTime(LocalDateTime.of(2026, 1, 2, 8, 0));
        return config;
    }
}
