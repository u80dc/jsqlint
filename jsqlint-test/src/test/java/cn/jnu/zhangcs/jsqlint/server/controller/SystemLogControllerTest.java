package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.entity.SystemLog;
import cn.jnu.zhangcs.jsqlint.server.service.SystemLogService;
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
 * {@link SystemLogController} 单元测试（standalone MockMvc，service 全部 Mock）。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class SystemLogControllerTest {

    @Mock
    private SystemLogService systemLogService;

    @InjectMocks
    private SystemLogController systemLogController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(systemLogController)
                .setControllerAdvice(new GlobalExceptionHandler())
                // standalone 默认 StringHttpMessageConverter 为 ISO-8859-1，中文响应会丢字，需显式指定 UTF-8
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new JacksonJsonHttpMessageConverter())
                // standalone 默认不注册安全相关解析器，@AuthenticationPrincipal 会被兜底绑定成空对象，需显式注册
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    private SystemLog log(Long id, String operationType, String message) {
        SystemLog log = new SystemLog();
        log.setId(id);
        log.setOperationType(operationType);
        log.setOperationUserId(7L);
        log.setOperationUserName("admin");
        log.setOperationMessage(message);
        log.setRelatedId(5L);
        return log;
    }

    @Test
    void list_withFilters_returnsPage() throws Exception {
        Page<SystemLog> page = new Page<>(2, 10, 25);
        page.setRecords(List.of(log(1L, "CREATE", "创建用户")));
        when(systemLogService.getPage(2, 10, "CREATE", 7L,
                "2024-01-01 00:00:00", "2024-12-31 23:59:59")).thenReturn(page);

        mockMvc.perform(get("/api/system/logs")
                        .param("page", "2")
                        .param("size", "10")
                        .param("operationType", "CREATE")
                        .param("operationUserId", "7")
                        .param("startTime", "2024-01-01 00:00:00")
                        .param("endTime", "2024-12-31 23:59:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(2))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.total").value(25))
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].id").value(1))
                .andExpect(jsonPath("$.records[0].operationType").value("CREATE"))
                .andExpect(jsonPath("$.records[0].operationMessage").value("创建用户"))
                .andExpect(jsonPath("$.records[0].operationUserId").value(7));
    }

    @Test
    void list_defaultParams_returnsFirstPage() throws Exception {
        Page<SystemLog> page = new Page<>(1, 20);
        when(systemLogService.getPage(1, 20, null, null, null, null)).thenReturn(page);

        mockMvc.perform(get("/api/system/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void list_bizException_returns400() throws Exception {
        when(systemLogService.getPage(1, 20, null, null, null, null))
                .thenThrow(new BizException("日志查询失败"));

        mockMvc.perform(get("/api/system/logs"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("日志查询失败"));
    }

    @Test
    void getByRelatedId_returnsPage() throws Exception {
        Page<SystemLog> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(log(1L, "DELETE", "删除凭证")));
        when(systemLogService.getByRelatedId(1, 10, 5L)).thenReturn(page);

        mockMvc.perform(get("/api/system/logs/related/5")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(1))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.records[0].operationType").value("DELETE"))
                .andExpect(jsonPath("$.records[0].operationMessage").value("删除凭证"))
                .andExpect(jsonPath("$.records[0].relatedId").value(5));
    }
}
