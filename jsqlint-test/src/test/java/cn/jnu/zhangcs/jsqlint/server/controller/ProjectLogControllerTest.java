package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectLog;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectLogService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ProjectLogController} 单元测试（standalone MockMvc，service 全部 Mock）。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class ProjectLogControllerTest {

    @Mock
    private ProjectLogService projectLogService;

    @InjectMocks
    private ProjectLogController projectLogController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(projectLogController)
                .setControllerAdvice(new GlobalExceptionHandler())
                // standalone 默认 StringHttpMessageConverter 为 ISO-8859-1，中文响应会丢字，需显式指定 UTF-8
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new JacksonJsonHttpMessageConverter())
                // standalone 默认不注册安全相关解析器，@AuthenticationPrincipal 会被兜底绑定成空对象，需显式注册
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    private ProjectLog log(Long id, String operationType, String message) {
        ProjectLog log = new ProjectLog();
        log.setId(id);
        log.setProjectId(3L);
        log.setOperationType(operationType);
        log.setOperationUserId(9L);
        log.setOperationUserName("admin");
        log.setOperationMessage(message);
        log.setRelatedId(8L);
        return log;
    }

    @Test
    void list_withFilters_returnsPage() throws Exception {
        Page<ProjectLog> page = new Page<>(2, 5, 7);
        page.setRecords(List.of(log(1L, "SYNC", "同步项目代码")));
        when(projectLogService.getPage(2, 5, 3L, "SYNC", 9L,
                "2024-01-01 00:00:00", "2024-12-31 23:59:59")).thenReturn(page);

        mockMvc.perform(get("/api/project/logs")
                        .param("page", "2")
                        .param("size", "5")
                        .param("projectId", "3")
                        .param("operationType", "SYNC")
                        .param("operationUserId", "9")
                        .param("startTime", "2024-01-01 00:00:00")
                        .param("endTime", "2024-12-31 23:59:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.total").value(7))
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].id").value(1))
                .andExpect(jsonPath("$.records[0].projectId").value(3))
                .andExpect(jsonPath("$.records[0].operationType").value("SYNC"))
                .andExpect(jsonPath("$.records[0].operationMessage").value("同步项目代码"));
    }

    @Test
    void list_defaultParams_returnsFirstPage() throws Exception {
        Page<ProjectLog> page = new Page<>(1, 20);
        when(projectLogService.getPage(1, 20, null, null, null, null, null)).thenReturn(page);

        mockMvc.perform(get("/api/project/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void list_bizException_returns400() throws Exception {
        when(projectLogService.getPage(1, 20, null, null, null, null, null))
                .thenThrow(new BizException("项目日志查询失败"));

        mockMvc.perform(get("/api/project/logs"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("项目日志查询失败"));
    }

    @Test
    void getByProjectId_returnsPage() throws Exception {
        Page<ProjectLog> page = new Page<>(1, 10, 2);
        page.setRecords(List.of(log(1L, "COMPILE", "编译项目")));
        when(projectLogService.getByProjectId(1, 10, 3L)).thenReturn(page);

        mockMvc.perform(get("/api/project/logs/3")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(1))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.records[0].projectId").value(3))
                .andExpect(jsonPath("$.records[0].operationType").value("COMPILE"))
                .andExpect(jsonPath("$.records[0].operationMessage").value("编译项目"));
    }

    @Test
    void getByRelatedId_returnsPage() throws Exception {
        Page<ProjectLog> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(log(1L, "RULE_ENABLE", "启用规则")));
        when(projectLogService.getByRelatedId(1, 10, 3L, 8L)).thenReturn(page);

        mockMvc.perform(get("/api/project/logs/3/related/8")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(1))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.records[0].operationType").value("RULE_ENABLE"))
                .andExpect(jsonPath("$.records[0].relatedId").value(8));
    }
}
