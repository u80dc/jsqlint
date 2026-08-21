package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.entity.Notification;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.NotificationService;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link NotificationController} 单元测试（standalone MockMvc，service 全部 Mock）。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private NotificationController notificationController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(notificationController)
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
    void listNotifications_authenticated_returnsVoListWithTypeDescriptions() throws Exception {
        Notification first = new Notification();
        first.setId(10L);
        first.setType("ANALYSIS_COMPLETE");
        first.setTitle("分析完成");
        first.setContent("项目 analysis 已完成");
        first.setStatus(0);
        first.setCreateTime(LocalDateTime.of(2026, 1, 1, 10, 0));

        Notification second = new Notification();
        second.setId(11L);
        second.setType("SOMETHING_ELSE");
        second.setTitle("其他通知");
        second.setContent("未知类型通知");
        second.setStatus(1);

        when(notificationService.listByUserId(7L)).thenReturn(List.of(first, second));

        mockMvc.perform(get("/api/notifications")
                        .principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[0].type").value("ANALYSIS_COMPLETE"))
                // 已知类型映射为中文描述，未知类型回退为“系统通知”
                .andExpect(jsonPath("$[0].typeDescription").value("分析完成"))
                .andExpect(jsonPath("$[0].title").value("分析完成"))
                .andExpect(jsonPath("$[0].status").value(0))
                .andExpect(jsonPath("$[1].id").value(11))
                .andExpect(jsonPath("$[1].typeDescription").value("系统通知"));
    }

    @Test
    void getUnreadCount_authenticated_returnsCount() throws Exception {
        when(notificationService.getUnreadCount(7L)).thenReturn(3);

        mockMvc.perform(get("/api/notifications/unread-count")
                        .principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3));
    }

    @Test
    void markAsRead_validId_returnsSuccessMessage() throws Exception {
        mockMvc.perform(put("/api/notifications/5/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("标记已读成功"));

        verify(notificationService).markAsRead(5L);
    }

    @Test
    void markAsRead_bizExceptionMappedTo400() throws Exception {
        doThrow(new BizException("通知不存在")).when(notificationService).markAsRead(99L);

        mockMvc.perform(put("/api/notifications/99/read"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("通知不存在"));
    }

    @Test
    void markAllAsRead_authenticated_returnsSuccessMessage() throws Exception {
        mockMvc.perform(put("/api/notifications/read-all")
                        .principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("全部标记已读成功"));

        verify(notificationService).markAllAsRead(7L);
    }

    @Test
    void deleteNotification_authenticated_returnsSuccessMessage() throws Exception {
        mockMvc.perform(delete("/api/notifications/5")
                        .principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("通知删除成功"));

        verify(notificationService).deleteByIdAndUserId(5L, 7L);
    }
}
