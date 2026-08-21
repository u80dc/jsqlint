package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SystemSettingsVO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.SystemConfigService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SystemConfigController} 单元测试（standalone MockMvc，service 全部 Mock）。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class SystemConfigControllerTest {

    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private SystemConfigController systemConfigController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(systemConfigController)
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
    void getSettings_returnsCurrentSettings() throws Exception {
        SystemSettingsVO vo = buildSettings("blue", 60);
        when(systemConfigService.getSettings()).thenReturn(vo);

        mockMvc.perform(get("/api/system/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basic.themeColor").value("blue"))
                .andExpect(jsonPath("$.basic.language").value("zh-CN"))
                .andExpect(jsonPath("$.projectAnalysis.analysisTaskTimeoutMinutes").value(60));
    }

    @Test
    void saveSettings_validBody_returnsSavedSettingsWithOperatorFromPrincipal() throws Exception {
        SystemSettingsVO vo = buildSettings("green", 120);
        when(systemConfigService.saveSettings(any(), eq(7L))).thenReturn(vo);

        mockMvc.perform(put("/api/system/settings")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"basic\":{\"themeColor\":\"green\",\"language\":\"zh-CN\"},"
                                + "\"projectAnalysis\":{\"analysisTaskTimeoutMinutes\":120}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basic.themeColor").value("green"))
                .andExpect(jsonPath("$.projectAnalysis.analysisTaskTimeoutMinutes").value(120));
    }

    @Test
    void saveSettings_invalidPort_returns400() throws Exception {
        mockMvc.perform(put("/api/system/settings")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"messageNotification\":{\"port\":99999}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数校验失败"))
                .andExpect(jsonPath("$.errors['messageNotification.port']").value("SMTP端口不能大于65535"));
    }

    @Test
    void resetSettings_authenticated_returnsResetSettings() throws Exception {
        SystemSettingsVO vo = buildSettings("blue", 60);
        when(systemConfigService.resetSettings(7L)).thenReturn(vo);

        mockMvc.perform(post("/api/system/settings/reset")
                        .principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basic.themeColor").value("blue"))
                .andExpect(jsonPath("$.projectAnalysis.analysisTaskTimeoutMinutes").value(60));
    }

    @Test
    void resetSettings_noPrincipal_passesNullOperator() throws Exception {
        SystemSettingsVO vo = buildSettings("blue", 60);
        when(systemConfigService.resetSettings(isNull())).thenReturn(vo);

        mockMvc.perform(post("/api/system/settings/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basic.themeColor").value("blue"));
    }

    /**
     * 构造一个最小可断言的系统设置视图。
     */
    private SystemSettingsVO buildSettings(String themeColor, int timeoutMinutes) {
        return SystemSettingsVO.builder()
                .basic(SystemSettingsVO.BasicSettings.builder()
                        .themeColor(themeColor)
                        .language("zh-CN")
                        .build())
                .projectAnalysis(SystemSettingsVO.ProjectAnalysisSettings.builder()
                        .analysisTaskTimeoutMinutes(timeoutMinutes)
                        .maxConcurrentTasks(4)
                        .build())
                .build();
    }
}
