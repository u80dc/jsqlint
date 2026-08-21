package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewCurrentTaskVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewProjectSummaryVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewRecentProjectVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.OverviewTrendVO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.OverviewService;
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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link OverviewController} 单元测试（standalone MockMvc，service 全部 Mock）。
 * <p>控制器为纯委托实现，测试关注路由、当前用户透传与 period 默认值。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class OverviewControllerTest {

    @Mock
    private OverviewService overviewService;

    @InjectMocks
    private OverviewController overviewController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(overviewController)
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
    void getProjectSummary_authenticated_returnsSummaryVO() throws Exception {
        when(overviewService.getProjectSummary(7L)).thenReturn(OverviewProjectSummaryVO.builder()
                .totalProjects(3)
                .safeSqlCount(100)
                .warningSqlCount(20)
                .dangerSqlCount(5)
                .build());

        mockMvc.perform(get("/api/overview/projects/summary").principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalProjects").value(3))
                .andExpect(jsonPath("$.safeSqlCount").value(100))
                .andExpect(jsonPath("$.warningSqlCount").value(20))
                .andExpect(jsonPath("$.dangerSqlCount").value(5));

        verify(overviewService).getProjectSummary(7L);
    }

    @Test
    void listRecentProjects_authenticated_returnsRecentList() throws Exception {
        when(overviewService.listRecentProjects(7L)).thenReturn(List.of(OverviewRecentProjectVO.builder()
                .projectId(1L)
                .projectName("demo")
                .versionId(9L)
                .ref("main")
                .commitId("abc123")
                .commitMessage("初始提交")
                .status(2)
                .statusDescription("执行成功")
                .analysisTime(LocalDateTime.of(2026, 1, 15, 9, 30))
                .build()));

        mockMvc.perform(get("/api/overview/recent-projects").principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].projectId").value(1))
                .andExpect(jsonPath("$[0].projectName").value("demo"))
                .andExpect(jsonPath("$[0].commitMessage").value("初始提交"))
                .andExpect(jsonPath("$[0].statusDescription").value("执行成功"));

        verify(overviewService).listRecentProjects(7L);
    }

    @Test
    void getAnalysisTrend_noPeriodParam_defaultsToWeek() throws Exception {
        when(overviewService.getAnalysisTrend(7L, "week")).thenReturn(OverviewTrendVO.builder()
                .period("week")
                .labels(List.of("2026-W01", "2026-W02"))
                .safe(List.of(10, 12))
                .warning(List.of(2, 3))
                .danger(List.of(1, 0))
                .build());

        mockMvc.perform(get("/api/overview/analysis-trend").principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("week"))
                .andExpect(jsonPath("$.labels.length()").value(2))
                .andExpect(jsonPath("$.safe[1]").value(12))
                .andExpect(jsonPath("$.danger[1]").value(0));

        // 未传 period 时应使用默认值 week
        verify(overviewService).getAnalysisTrend(7L, "week");
    }

    @Test
    void getAnalysisTrend_explicitPeriod_passesPeriodToService() throws Exception {
        when(overviewService.getAnalysisTrend(7L, "month")).thenReturn(OverviewTrendVO.builder()
                .period("month")
                .labels(List.of("2026-01", "2026-02"))
                .safe(List.of(40))
                .warning(List.of(8))
                .danger(List.of(2))
                .build());

        mockMvc.perform(get("/api/overview/analysis-trend")
                        .param("period", "month")
                        .principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("month"))
                .andExpect(jsonPath("$.labels[0]").value("2026-01"));

        verify(overviewService).getAnalysisTrend(7L, "month");
    }

    @Test
    void listCurrentTasks_authenticated_returnsTaskList() throws Exception {
        when(overviewService.listCurrentTasks(7L)).thenReturn(List.of(OverviewCurrentTaskVO.builder()
                .id(100L)
                .projectId(1L)
                .projectName("demo")
                .versionId(9L)
                .type(0)
                .typeDescription("同步代码")
                .status(1)
                .statusDescription("执行中")
                .retryCount(0)
                .maxRetries(3)
                .build()));

        mockMvc.perform(get("/api/overview/current-tasks").principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(100))
                .andExpect(jsonPath("$[0].projectName").value("demo"))
                .andExpect(jsonPath("$[0].typeDescription").value("同步代码"))
                .andExpect(jsonPath("$[0].statusDescription").value("执行中"));

        verify(overviewService).listCurrentTasks(7L);
    }
}
