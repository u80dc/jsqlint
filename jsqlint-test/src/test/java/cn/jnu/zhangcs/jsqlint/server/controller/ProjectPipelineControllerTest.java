package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.AnalysisService;
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

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ProjectPipelineController} 单元测试（standalone MockMvc，service 全部 Mock）。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class ProjectPipelineControllerTest {

    @Mock
    private AnalysisService analysisService;

    @InjectMocks
    private ProjectPipelineController projectPipelineController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(projectPipelineController)
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

    private UsernamePasswordAuthenticationToken login(Long userId) {
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(new LoginUser("admin", userId), "password");
        SecurityContextHolder.getContext().setAuthentication(token);
        return token;
    }

    @Test
    void sync_authenticated_returnsOk() throws Exception {
        mockMvc.perform(post("/api/projects/1/sync").principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(analysisService).sync(1L, 7L);
    }

    @Test
    void compile_authenticated_returnsOk() throws Exception {
        mockMvc.perform(post("/api/projects/1/compile").principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(analysisService).compile(1L, 7L);
    }

    @Test
    void analyze_authenticated_returnsOk() throws Exception {
        mockMvc.perform(post("/api/projects/1/analyze").principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(analysisService).analyze(1L, 7L);
    }

    @Test
    void audit_authenticated_returnsOk() throws Exception {
        mockMvc.perform(post("/api/projects/1/audit").principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(analysisService).audit(1L, 7L);
    }

    @Test
    void report_authenticated_returnsTaskId() throws Exception {
        when(analysisService.report(1L, 7L)).thenReturn(555L);

        mockMvc.perform(post("/api/projects/1/reports").principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(content().string("555"));
    }

    @Test
    void execute_authenticated_returnsTrue() throws Exception {
        when(analysisService.execute(1L, 7L)).thenReturn(true);

        mockMvc.perform(post("/api/projects/1/execute").principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(content().string("true"));
    }

    @Test
    void sync_bizExceptionMappedTo400() throws Exception {
        doThrow(new BizException("项目正在同步中")).when(analysisService).sync(1L, 7L);

        mockMvc.perform(post("/api/projects/1/sync").principal(login(7L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("项目正在同步中"));
    }
}
