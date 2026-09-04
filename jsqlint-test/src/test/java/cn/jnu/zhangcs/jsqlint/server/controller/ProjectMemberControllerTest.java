package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectMember;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
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

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ProjectMemberController} 单元测试（standalone MockMvc，service 全部 Mock）。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class ProjectMemberControllerTest {

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private ProjectMemberController projectMemberController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(projectMemberController)
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

    /** 写操作要求项目管理员，默认放行。 */
    private void loginAsProjectAdmin(Long userId, Long projectId) {
        login(userId);
        when(projectService.isAdmin(projectId, userId)).thenReturn(true);
    }

    @Test
    void addMember_validParams_returnsOk() throws Exception {
        loginAsProjectAdmin(7L, 5L);
        mockMvc.perform(post("/api/projects/5/members")
                        .principal(login(7L))
                        .param("userId", "9")
                        .param("isAdmin", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(projectService).addMember(5L, 9L, true);
    }

    @Test
    void addMember_bizExceptionMappedTo400() throws Exception {
        loginAsProjectAdmin(7L, 5L);
        doThrow(new BizException("用户已是项目成员")).when(projectService).addMember(5L, 9L, false);

        mockMvc.perform(post("/api/projects/5/members")
                        .principal(login(7L))
                        .param("userId", "9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("用户已是项目成员"));
    }

    @Test
    void addMembers_validBody_returnsOk() throws Exception {
        loginAsProjectAdmin(7L, 5L);
        mockMvc.perform(post("/api/projects/5/members/batch")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("[9,10,11]"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(projectService).addMembers(5L, List.of(9L, 10L, 11L), false);
    }

    @Test
    void addGroupMembers_validParams_returnsOk() throws Exception {
        loginAsProjectAdmin(7L, 5L);
        mockMvc.perform(post("/api/projects/5/members/group")
                        .principal(login(7L))
                        .param("groupId", "3")
                        .param("isAdmin", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(projectService).addMembersByGroup(5L, 3L, true);
    }

    @Test
    void removeMember_validPath_returnsOk() throws Exception {
        loginAsProjectAdmin(7L, 5L);
        mockMvc.perform(delete("/api/projects/5/members/9")
                        .principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(projectService).removeMember(5L, 9L);
    }

    @Test
    void updateRole_validParams_returnsOk() throws Exception {
        loginAsProjectAdmin(7L, 5L);
        mockMvc.perform(put("/api/projects/5/members/9/role")
                        .principal(login(7L))
                        .param("isAdmin", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(projectService).updateMemberRole(5L, 9L, true);
    }

    @Test
    void getMembers_returnsVOListWithRoleDescriptions() throws Exception {
        ProjectMember admin = new ProjectMember();
        admin.setId(1L);
        admin.setProjectId(5L);
        admin.setUserId(9L);
        admin.setRole(1);
        admin.setAccount("zhangcs");
        admin.setUsername("张三");
        admin.setJoinTime(LocalDateTime.of(2026, 1, 1, 10, 0));

        ProjectMember normal = new ProjectMember();
        normal.setId(2L);
        normal.setProjectId(5L);
        normal.setUserId(10L);
        normal.setRole(0);
        normal.setAccount("lisi");
        normal.setUsername("李四");

        when(projectService.getMembers(5L)).thenReturn(List.of(admin, normal));

        mockMvc.perform(get("/api/projects/5/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].userId").value(9))
                .andExpect(jsonPath("$[0].username").value("张三"))
                // role=1 → 项目管理员，role=0 → 普通用户（由枚举翻译）
                .andExpect(jsonPath("$[0].roleDescription").value("项目管理员"))
                .andExpect(jsonPath("$[1].roleDescription").value("普通用户"));
    }

    @Test
    void checkIsAdmin_authenticated_returnsTrue() throws Exception {
        when(projectService.isAdmin(5L, 7L)).thenReturn(true);

        mockMvc.perform(get("/api/projects/5/members/check").principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(content().string("true"));

        verify(projectService).isAdmin(5L, 7L);
    }
}
