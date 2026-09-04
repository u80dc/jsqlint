package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.converter.ProjectConverter;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.entity.Credential;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersion;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.model.dto.ProjectDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectReportVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectStatusVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectVersionVO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ProjectController} 单元测试（standalone MockMvc，service/converter 全部 Mock）。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class ProjectControllerTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private WorkspaceService workspaceService;

    @Mock
    private ProjectConverter converter;

    @Mock
    private CredentialService credentialService;

    @Mock
    private ProjectVersionService projectVersionService;

    @Mock
    private UserService userService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private ProjectReportService projectReportService;

    @Mock
    private ProjectVersionStepService projectVersionStepService;

    @InjectMocks
    private ProjectController projectController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(projectController)
                .setControllerAdvice(new GlobalExceptionHandler())
                // standalone 默认 StringHttpMessageConverter 为 ISO-8859-1，中文响应会丢字，需显式指定 UTF-8；
                // setMessageConverters 会整体替换默认转换器，下载报告返回 byte[]，还需补上 ByteArrayHttpMessageConverter
                .setMessageConverters(
                        new ByteArrayHttpMessageConverter(),
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

    private Project project(Long id, String name, Long createUserId) {
        Project project = new Project();
        project.setId(id);
        project.setName(name);
        project.setGitUrl("https://github.com/example/repo.git");
        project.setRef("main");
        project.setStatus(1);
        project.setCreateUserId(createUserId);
        return project;
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setAccount("account-" + id);
        user.setUsername(username);
        return user;
    }

    @Test
    void create_validBodyWithWorkspace_returnsProjectVO() throws Exception {
        Project input = project(null, "demo", 7L);
        Project created = project(1L, "demo", 7L);
        when(converter.fromDTO(any(ProjectDTO.class), eq(7L))).thenReturn(input);
        when(projectService.create(input)).thenReturn(created);
        when(converter.toVO(created, "admin")).thenReturn(ProjectVO.builder()
                .id(1L).name("demo").gitUrl("https://github.com/example/repo.git").build());

        mockMvc.perform(post("/api/projects")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"demo\",\"gitUrl\":\"https://github.com/example/repo.git\",\"workspaceId\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("demo"))
                .andExpect(jsonPath("$.gitUrl").value("https://github.com/example/repo.git"));

        // workspaceId 大于 0 时应同时把项目加入工作空间
        verify(workspaceService).addProject(5L, 1L);
    }

    @Test
    void create_blankGitUrl_returns400() throws Exception {
        // gitUrl 上的 @NotBlank 先于控制器内的手动判空触发，返回 400 校验错误体
        mockMvc.perform(post("/api/projects")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"demo\",\"gitUrl\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数校验失败"))
                .andExpect(jsonPath("$.errors.gitUrl").value("Git地址不能为空"));
    }

    @Test
    void updateProject_existing_returnsUpdatedVO() throws Exception {
        Project existing = project(1L, "demo", 9L);
        User creator = user(9L, "zhang");
        when(projectService.getById(1L)).thenReturn(existing);
        when(projectService.updateById(1L, existing)).thenReturn(existing);
        when(userService.getById(9L)).thenReturn(creator);
        when(converter.toVO(existing, "zhang")).thenReturn(ProjectVO.builder()
                .id(1L).name("demo").ref("dev").build());

        mockMvc.perform(put("/api/projects/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"demo\",\"ref\":\"dev\",\"description\":\"示例项目\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.ref").value("dev"));
    }

    @Test
    void updateCredential_withCredentialId_setsAndReturnsVO() throws Exception {
        Project existing = project(1L, "demo", 9L);
        User creator = user(9L, "zhang");
        when(projectService.getById(1L)).thenReturn(existing);
        when(projectService.updateById(eq(1L), any(Project.class))).thenReturn(existing);
        when(userService.getById(9L)).thenReturn(creator);
        when(converter.toVO(existing, "zhang")).thenReturn(ProjectVO.builder().id(1L).credentialId(3L).build());

        mockMvc.perform(put("/api/projects/1/credential").param("credentialId", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.credentialId").value(3));

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectService).updateById(eq(1L), captor.capture());
        assertEquals(3L, captor.getValue().getCredentialId());
    }

    @Test
    void updateCredential_credentialIdMinusOne_clearsCredential() throws Exception {
        Project existing = project(1L, "demo", 9L);
        existing.setCredentialId(5L);
        User creator = user(9L, "zhang");
        when(projectService.getById(1L)).thenReturn(existing);
        when(projectService.updateById(eq(1L), any(Project.class))).thenReturn(existing);
        when(userService.getById(9L)).thenReturn(creator);
        when(converter.toVO(existing, "zhang")).thenReturn(ProjectVO.builder().id(1L).build());

        mockMvc.perform(put("/api/projects/1/credential").param("credentialId", "-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));

        // credentialId 为 -1 表示取消关联，应写入 null
        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectService).updateById(eq(1L), captor.capture());
        assertNull(captor.getValue().getCredentialId());
    }

    @Test
    void updateCredential_projectNotFound_returns400() throws Exception {
        when(projectService.getById(99L)).thenReturn(null);

        mockMvc.perform(put("/api/projects/99/credential").param("credentialId", "3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("项目不存在"));
    }

    @Test
    void deleteProject_existing_returnsOk() throws Exception {
        mockMvc.perform(delete("/api/projects/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(projectService).delete(1L);
    }

    @Test
    void getProject_found_returnsVOWithCreatorName() throws Exception {
        Project existing = project(1L, "demo", 9L);
        when(projectService.getById(1L)).thenReturn(existing);
        when(userService.getById(9L)).thenReturn(user(9L, "zhang"));
        when(converter.toVO(existing, "zhang")).thenReturn(ProjectVO.builder()
                .id(1L).name("demo").createUserName("zhang").build());

        mockMvc.perform(get("/api/projects/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.createUserName").value("zhang"));
    }

    @Test
    void getProject_notFound_returns200WithEmptyBody() throws Exception {
        when(projectService.getById(99L)).thenReturn(null);

        mockMvc.perform(get("/api/projects/99"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void listProjects_withFilter_returnsVOList() throws Exception {
        Project existing = project(1L, "demo", 9L);
        User creator = user(9L, "zhang");
        when(projectService.getByKeyword("demo", true)).thenReturn(List.of(existing));
        when(userService.getByIds(any())).thenReturn(List.of(creator));
        when(converter.toProjectVOList(List.of(existing), List.of(creator)))
                .thenReturn(List.of(ProjectVO.builder().id(1L).name("demo").build()));

        mockMvc.perform(get("/api/projects")
                        .param("keyword", "demo")
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("demo"));
    }

    @Test
    void listProjects_noMatch_returnsEmptyArray() throws Exception {
        when(projectService.getByKeyword(null, null)).thenReturn(List.of());

        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listProjectVersions_returnsVOListWithOperatorNames() throws Exception {
        Project existing = project(1L, "demo", 9L);
        ProjectVersion version = new ProjectVersion();
        version.setId(2L);
        version.setProjectId(1L);
        version.setRef("main");
        version.setOperatorId(9L);
        when(projectService.getById(1L)).thenReturn(existing);
        when(projectVersionService.getList(1L)).thenReturn(List.of(version));
        when(userService.getByIds(any())).thenReturn(List.of(user(9L, "zhang")));
        when(converter.toVO(version, "demo", "zhang")).thenReturn(ProjectVersionVO.builder()
                .id(2L).projectId(1L).projectName("demo").operatorId(9L).operatorName("zhang").build());

        mockMvc.perform(get("/api/projects/1/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(2))
                .andExpect(jsonPath("$[0].projectName").value("demo"))
                .andExpect(jsonPath("$[0].operatorName").value("zhang"));
    }

    @Test
    void getProjectStatus_found_returnsStatusVO() throws Exception {
        when(projectService.getById(1L)).thenReturn(project(1L, "demo", 9L));
        when(projectVersionStepService.getProjectStatus(1L)).thenReturn(ProjectStatusVO.builder()
                .projectId(1L).versionId(2L).overallStatus(1).overallStatusName("执行中").steps(List.of()).build());

        mockMvc.perform(get("/api/projects/1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(1))
                .andExpect(jsonPath("$.versionId").value(2))
                .andExpect(jsonPath("$.overallStatusName").value("执行中"));
    }

    @Test
    void getProjectStatus_projectNotFound_returns400() throws Exception {
        when(projectService.getById(99L)).thenReturn(null);

        mockMvc.perform(get("/api/projects/99/status"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("项目不存在"));
    }

    @Test
    void getReport_withoutVersionId_returnsLatestReport() throws Exception {
        when(projectService.getById(1L)).thenReturn(project(1L, "demo", 9L));
        when(projectReportService.getLatestByProjectId(1L)).thenReturn(ProjectReportVO.builder()
                .id(10L).projectId(1L).score(90).rating("A").build());

        mockMvc.perform(get("/api/projects/1/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.score").value(90))
                .andExpect(jsonPath("$.rating").value("A"));

        verify(projectReportService).getLatestByProjectId(1L);
    }

    @Test
    void getReport_withVersionId_returnsVersionReport() throws Exception {
        when(projectService.getById(1L)).thenReturn(project(1L, "demo", 9L));
        when(projectReportService.getByProjectIdAndVersionId(1L, 2L)).thenReturn(ProjectReportVO.builder()
                .id(11L).projectId(1L).versionId(2L).score(80).rating("B").build());

        mockMvc.perform(get("/api/projects/1/reports").param("versionId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.versionId").value(2));

        verify(projectReportService).getByProjectIdAndVersionId(1L, 2L);
    }

    @Test
    void downloadReport_withoutVersionId_returnsPdfWithDispositionHeader() throws Exception {
        byte[] pdf = new byte[]{0x25, 0x50, 0x46, 0x46};
        when(projectService.getById(1L)).thenReturn(project(1L, "demo", 9L));
        when(projectReportService.downloadLatestPdf(1L)).thenReturn(pdf);

        mockMvc.perform(get("/api/projects/1/reports/download"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(pdf))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"demo-1.pdf\"; filename*=UTF-8''demo-1.pdf"));

        verify(projectReportService).downloadLatestPdf(1L);
    }

    @Test
    void downloadReport_withVersionId_downloadsVersionPdf() throws Exception {
        byte[] pdf = new byte[]{0x25, 0x50};
        when(projectService.getById(1L)).thenReturn(project(1L, "demo", 9L));
        when(projectReportService.downloadPdf(1L, 2L)).thenReturn(pdf);

        mockMvc.perform(get("/api/projects/1/reports/download").param("versionId", "2"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(pdf));

        verify(projectReportService).downloadPdf(1L, 2L);
    }

    @Test
    void downloadReport_projectNotFound_returns400() throws Exception {
        when(projectService.getById(99L)).thenReturn(null);

        mockMvc.perform(get("/api/projects/99/reports/download"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("项目不存在"));
    }

    @Test
    void listCredentials_authenticated_returnsCredentialVOs() throws Exception {
        Credential credential = new Credential();
        credential.setId(3L);
        credential.setName("团队凭证");
        credential.setVisibility(1);
        credential.setStatus(1);
        credential.setAccount("git-user");
        credential.setToken("token");
        credential.setPassword("pwd");
        credential.setCreateUserId(7L);
        when(credentialService.listOptional(7L)).thenReturn(List.of(credential));
        when(userService.getByIds(any())).thenReturn(List.of(user(7L, "admin")));

        mockMvc.perform(get("/api/projects/credentials").principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("团队凭证"))
                // CredentialConverter 为静态真实转换：visibility=1 → 公开，创建人取用户名
                .andExpect(jsonPath("$[0].visibilityDescription").value("公开"))
                .andExpect(jsonPath("$[0].createUserName").value("admin"));
    }
}
