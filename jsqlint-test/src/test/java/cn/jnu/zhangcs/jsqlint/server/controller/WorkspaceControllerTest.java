package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.entity.Workspace;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
import cn.jnu.zhangcs.jsqlint.server.service.WorkspaceService;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link WorkspaceController} 单元测试（standalone MockMvc，service 全部 Mock）。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceControllerTest {

    @Mock
    private WorkspaceService workspaceService;

    @Mock
    private UserService userService;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private WorkspaceController workspaceController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(workspaceController)
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

    private User user(Long id, String name) {
        User user = new User();
        user.setId(id);
        user.setUsername(name);
        return user;
    }

    @Test
    void create_validName_returnsWorkspaceVO() throws Exception {
        Workspace created = new Workspace();
        created.setId(1L);
        created.setName("我的工作区");
        created.setCreatorId(7L);
        // 新建工作区项目数为 0，避免走 list() 兜底查询
        created.setProjectCount(0);
        when(workspaceService.create(7L, "我的工作区")).thenReturn(created);
        when(userService.getById(7L)).thenReturn(user(7L, "admin"));

        mockMvc.perform(post("/api/workspaces")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"我的工作区\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("我的工作区"))
                .andExpect(jsonPath("$.creatorId").value(7))
                .andExpect(jsonPath("$.creatorName").value("admin"))
                .andExpect(jsonPath("$.projectCount").value(0));
    }

    @Test
    void create_noPrincipal_returns400() throws Exception {
        mockMvc.perform(post("/api/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"我的工作区\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请先登录"));
    }

    @Test
    void create_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/workspaces")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数校验失败"))
                .andExpect(jsonPath("$.errors.name").value("工作空间名称不能为空"));
    }

    @Test
    void delete_invokesServiceAndReturns200() throws Exception {
        mockMvc.perform(delete("/api/workspaces/5"))
                .andExpect(status().isOk());

        verify(workspaceService).delete(5L);
    }

    @Test
    void getById_existing_returnsWorkspaceVO() throws Exception {
        Workspace workspace = new Workspace();
        workspace.setId(1L);
        workspace.setName("我的工作区");
        workspace.setCreatorId(7L);
        // 实体已带项目数，直接使用，无需 list() 兜底
        workspace.setProjectCount(5);
        when(workspaceService.getById(1L)).thenReturn(workspace);
        when(userService.getById(7L)).thenReturn(user(7L, "admin"));

        mockMvc.perform(get("/api/workspaces/1")
                        .principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("我的工作区"))
                .andExpect(jsonPath("$.creatorName").value("admin"))
                .andExpect(jsonPath("$.projectCount").value(5));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        when(workspaceService.getById(404L)).thenReturn(null);

        mockMvc.perform(get("/api/workspaces/404")
                        .principal(login(7L)))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_returnsWorkspacesWithCreatorNames() throws Exception {
        Workspace first = new Workspace();
        first.setId(1L);
        first.setName("工作区一");
        first.setCreatorId(7L);
        first.setProjectCount(2);
        Workspace second = new Workspace();
        second.setId(2L);
        second.setName("工作区二");
        second.setCreatorId(8L);
        second.setProjectCount(0);
        when(workspaceService.list(7L)).thenReturn(List.of(first, second));
        when(userService.getByIds(any())).thenReturn(List.of(user(7L, "admin"), user(8L, "zhangsan")));

        mockMvc.perform(get("/api/workspaces")
                        .principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].creatorName").value("admin"))
                .andExpect(jsonPath("$[0].projectCount").value(2))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].creatorName").value("zhangsan"));
    }

    @Test
    void addProject_invokesServiceAndReturns200() throws Exception {
        mockMvc.perform(post("/api/workspaces/1/projects/2")
                        .principal(login(7L)))
                .andExpect(status().isOk());

        verify(workspaceService).addProject(1L, 2L);
    }

    @Test
    void removeProject_invokesServiceAndReturns200() throws Exception {
        mockMvc.perform(delete("/api/workspaces/1/projects/2")
                        .principal(login(7L)))
                .andExpect(status().isOk());

        verify(workspaceService).removeProject(1L, 2L);
    }

    @Test
    void getProjects_withWorkspaceId_returnsProjectsOfWorkspace() throws Exception {
        Project project = new Project();
        project.setId(1L);
        project.setName("核心项目");
        project.setDescription("描述");
        project.setGitUrl("https://git.example.com/core.git");
        project.setRef("master");
        project.setStatus(1);
        project.setCreateUserId(7L);
        project.setCredentialId(9L);
        when(workspaceService.getProjects(3L)).thenReturn(List.of(project));

        Workspace workspace = new Workspace();
        workspace.setId(3L);
        workspace.setName("研发工作区");
        when(workspaceService.getWorkspaceByProjectId(1L)).thenReturn(workspace);

        mockMvc.perform(get("/api/workspaces/projects")
                        .param("workspaceId", "3")
                        .principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("核心项目"))
                .andExpect(jsonPath("$[0].gitUrl").value("https://git.example.com/core.git"))
                .andExpect(jsonPath("$[0].enabled").value(1))
                .andExpect(jsonPath("$[0].credentialId").value(9))
                .andExpect(jsonPath("$[0].workspaceId").value(3))
                .andExpect(jsonPath("$[0].workspaceName").value("研发工作区"));
    }

    @Test
    void getProjects_withoutWorkspaceId_returnsUserProjects() throws Exception {
        Project project = new Project();
        project.setId(2L);
        project.setName("个人项目");
        project.setStatus(1);
        when(projectService.getByUser(7L)).thenReturn(List.of(project));
        // 项目未归属任何工作区
        when(workspaceService.getWorkspaceByProjectId(2L)).thenReturn(null);

        mockMvc.perform(get("/api/workspaces/projects")
                        .principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(2))
                .andExpect(jsonPath("$[0].name").value("个人项目"));
    }
}
