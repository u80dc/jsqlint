package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.entity.Credential;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.CredentialService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link CredentialController} 单元测试（standalone MockMvc，service 全部 Mock）。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class CredentialControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private CredentialService credentialService;

    @InjectMocks
    private CredentialController credentialController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(credentialController)
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

    private <T> com.baomidou.mybatisplus.extension.plugins.pagination.Page<T> pageOf(List<T> records, long total) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<T> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
        page.setRecords(records);
        page.setTotal(total);
        return page;
    }

    private User user(Long id, String name) {
        User user = new User();
        user.setId(id);
        user.setUsername(name);
        return user;
    }

    @Test
    void createCredential_withToken_returnsCreatedVO() throws Exception {
        // service 返回带ID与创建人的实体，默认可见性应为 PRIVATE(0)、状态 1
        Credential created = new Credential();
        created.setId(1L);
        created.setName("公司Git凭证");
        created.setVisibility(0);
        created.setStatus(1);
        created.setAccount("gituser");
        created.setToken("tok123");
        created.setCreateUserId(7L);
        when(credentialService.createCredential(any(Credential.class))).thenReturn(created);
        when(userService.getById(7L)).thenReturn(user(7L, "admin"));

        mockMvc.perform(post("/api/credentials")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"公司Git凭证\",\"account\":\"gituser\",\"token\":\"tok123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("公司Git凭证"))
                .andExpect(jsonPath("$.visibility").value(0))
                .andExpect(jsonPath("$.visibilityDescription").value("私人"))
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.createUserId").value(7))
                .andExpect(jsonPath("$.createUserName").value("admin"));
    }

    @Test
    void createCredential_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/credentials")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"\",\"token\":\"tok123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数校验失败"))
                .andExpect(jsonPath("$.errors.name").value("凭证名称不能为空"));
    }

    @Test
    void createCredential_tokenAndPasswordBothBlank_returns500() throws Exception {
        // Controller 抛出 IllegalArgumentException，全局处理器按未知异常兜底为 500
        mockMvc.perform(post("/api/credentials")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"无凭证\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("系统内部错误: Token和密码至少需要配置一个"));
    }

    @Test
    void updateCredential_existing_returnsUpdatedVO() throws Exception {
        Credential existing = new Credential();
        existing.setId(1L);
        existing.setName("旧名称");
        existing.setVisibility(0);
        existing.setStatus(1);
        existing.setToken("tok123");
        existing.setCreateUserId(7L);
        when(credentialService.getCredentialById(1L)).thenReturn(existing);

        Credential updated = new Credential();
        updated.setId(1L);
        updated.setName("新名称");
        updated.setVisibility(0);
        updated.setStatus(0);
        updated.setToken("tok123");
        updated.setCreateUserId(7L);
        when(credentialService.updateCredential(any(Credential.class))).thenReturn(updated);
        when(userService.getById(7L)).thenReturn(user(7L, "admin"));

        mockMvc.perform(put("/api/credentials/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"新名称\",\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("新名称"))
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.createUserName").value("admin"));

        // DTO 非空字段应合并进实体
        ArgumentCaptor<Credential> captor = ArgumentCaptor.forClass(Credential.class);
        verify(credentialService).updateCredential(captor.capture());
        assertEquals("新名称", captor.getValue().getName());
        assertEquals(0, captor.getValue().getStatus());
        assertEquals("tok123", captor.getValue().getToken());
    }

    @Test
    void updateCredential_notFound_returns500() throws Exception {
        when(credentialService.getCredentialById(99L)).thenReturn(null);

        mockMvc.perform(put("/api/credentials/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"新名称\",\"token\":\"tok123\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("系统内部错误: 凭证不存在"));
    }

    @Test
    void deleteCredential_invokesServiceAndReturns200() throws Exception {
        mockMvc.perform(delete("/api/credentials/5"))
                .andExpect(status().isOk());

        verify(credentialService).deleteCredential(5L);
    }

    @Test
    void getCredential_existing_returnsVO() throws Exception {
        Credential credential = new Credential();
        credential.setId(1L);
        credential.setName("公司Git凭证");
        credential.setVisibility(1);
        credential.setStatus(1);
        credential.setAccount("gituser");
        credential.setCreateUserId(7L);
        when(credentialService.getCredentialById(1L)).thenReturn(credential);
        when(userService.getById(7L)).thenReturn(user(7L, "admin"));

        mockMvc.perform(get("/api/credentials/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("公司Git凭证"))
                .andExpect(jsonPath("$.visibility").value(1))
                .andExpect(jsonPath("$.visibilityDescription").value("公开"))
                .andExpect(jsonPath("$.createUserName").value("admin"));
    }

    @Test
    void getCredential_deleted_returns500() throws Exception {
        Credential credential = new Credential();
        credential.setId(1L);
        credential.setStatus(-1);
        when(credentialService.getCredentialById(1L)).thenReturn(credential);

        mockMvc.perform(get("/api/credentials/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("系统内部错误: 凭证不存在"));
    }

    @Test
    void listCredentials_withStatusFilter_returnsVOList() throws Exception {
        Credential first = new Credential();
        first.setId(1L);
        first.setName("公开凭证");
        first.setVisibility(1);
        first.setStatus(1);
        first.setCreateUserId(7L);
        Credential second = new Credential();
        second.setId(2L);
        second.setName("无主凭证");
        second.setVisibility(0);
        second.setStatus(1);
        when(credentialService.getCredentialPage(1, 1, 10)).thenReturn(pageOf(List.of(first, second), 2));
        when(userService.getByIds(any())).thenReturn(List.of(user(7L, "admin")));

        mockMvc.perform(get("/api/credentials").param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.list.length()").value(2))
                .andExpect(jsonPath("$.list[0].id").value(1))
                .andExpect(jsonPath("$.list[0].createUserName").value("admin"))
                .andExpect(jsonPath("$.list[1].id").value(2))
                .andExpect(jsonPath("$.list[1].name").value("无主凭证"));
    }

    @Test
    void listCredentials_empty_returnsEmptyList() throws Exception {
        when(credentialService.getCredentialPage(null, 1, 10)).thenReturn(pageOf(List.of(), 0));

        mockMvc.perform(get("/api/credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.list.length()").value(0));
    }

    @Test
    void toggleEnabled_disableStatus_returnsVO() throws Exception {
        Credential credential = new Credential();
        credential.setId(1L);
        credential.setName("公司Git凭证");
        credential.setVisibility(0);
        credential.setStatus(0);
        credential.setCreateUserId(7L);
        when(credentialService.toggleEnabled(1L, 0)).thenReturn(credential);
        when(userService.getById(7L)).thenReturn(user(7L, "admin"));

        mockMvc.perform(put("/api/credentials/1/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.createUserName").value("admin"));
    }
}
