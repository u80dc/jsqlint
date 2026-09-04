package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.entity.UserGroup;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.UserGroupService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link UserGroupController} 单元测试（standalone MockMvc，service 全部 Mock）。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class UserGroupControllerTest {

    @Mock
    private UserGroupService userGroupService;

    @Mock
    private UserService userService;

    @InjectMocks
    private UserGroupController userGroupController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(userGroupController)
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
    void create_validBody_returnsGroupVO() throws Exception {
        UserGroup created = new UserGroup();
        created.setId(1L);
        created.setName("研发组");
        created.setDescription("研发人员");
        created.setCreatorId(7L);
        created.setStatus(0);
        when(userGroupService.create(any(UserGroup.class))).thenReturn(created);
        when(userService.getById(7L)).thenReturn(user(7L, "admin"));
        when(userGroupService.getMemberCount(1L)).thenReturn(3);

        mockMvc.perform(post("/api/user-groups")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"研发组\",\"description\":\"研发人员\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("研发组"))
                .andExpect(jsonPath("$.description").value("研发人员"))
                .andExpect(jsonPath("$.creatorId").value(7))
                .andExpect(jsonPath("$.creatorName").value("admin"))
                .andExpect(jsonPath("$.memberCount").value(3));

        // 创建者应取自当前登录用户
        ArgumentCaptor<UserGroup> captor = ArgumentCaptor.forClass(UserGroup.class);
        verify(userGroupService).create(captor.capture());
        assertEquals(7L, captor.getValue().getCreatorId());
        assertEquals("研发组", captor.getValue().getName());
    }

    @Test
    void create_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/user-groups")
                        .principal(login(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数校验失败"))
                .andExpect(jsonPath("$.errors.name").value("用户组名称不能为空"));
    }

    @Test
    void update_existing_returnsUpdatedVO() throws Exception {
        UserGroup existing = new UserGroup();
        existing.setId(1L);
        existing.setName("旧名称");
        existing.setDescription("旧描述");
        existing.setCreatorId(7L);
        existing.setStatus(0);
        when(userGroupService.getById(1L)).thenReturn(existing);

        UserGroup updated = new UserGroup();
        updated.setId(1L);
        updated.setName("新名称");
        updated.setDescription("旧描述");
        updated.setCreatorId(7L);
        updated.setStatus(0);
        when(userGroupService.update(any(UserGroup.class))).thenReturn(updated);
        when(userService.getById(7L)).thenReturn(user(7L, "admin"));
        when(userGroupService.getMemberCount(1L)).thenReturn(2);

        mockMvc.perform(put("/api/user-groups/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"id\":1,\"name\":\"新名称\",\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("新名称"))
                .andExpect(jsonPath("$.description").value("旧描述"))
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.memberCount").value(2));

        // 仅合并 DTO 中的非空字段
        ArgumentCaptor<UserGroup> captor = ArgumentCaptor.forClass(UserGroup.class);
        verify(userGroupService).update(captor.capture());
        assertEquals("新名称", captor.getValue().getName());
        assertEquals("旧描述", captor.getValue().getDescription());
        assertEquals(0, captor.getValue().getStatus());
    }

    @Test
    void update_notFound_returns404() throws Exception {
        when(userGroupService.getById(404L)).thenReturn(null);

        mockMvc.perform(put("/api/user-groups/404")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"id\":404,\"name\":\"新名称\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_missingIdInBody_returns400() throws Exception {
        mockMvc.perform(put("/api/user-groups/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"name\":\"新名称\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数校验失败"))
                .andExpect(jsonPath("$.errors.id").value("用户组ID不能为空"));
    }

    @Test
    void delete_invokesServiceAndReturns200() throws Exception {
        mockMvc.perform(delete("/api/user-groups/5"))
                .andExpect(status().isOk());

        verify(userGroupService).delete(5L);
    }

    @Test
    void getById_existing_returnsVO() throws Exception {
        UserGroup group = new UserGroup();
        group.setId(1L);
        group.setName("研发组");
        group.setDescription("研发人员");
        group.setCreatorId(7L);
        group.setStatus(0);
        when(userGroupService.getById(1L)).thenReturn(group);
        when(userService.getById(7L)).thenReturn(user(7L, "admin"));
        when(userGroupService.getMemberCount(1L)).thenReturn(4);

        mockMvc.perform(get("/api/user-groups/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("研发组"))
                .andExpect(jsonPath("$.creatorName").value("admin"))
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.memberCount").value(4));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        when(userGroupService.getById(404L)).thenReturn(null);

        mockMvc.perform(get("/api/user-groups/404"))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_returnsGroupsWithMemberCounts() throws Exception {
        UserGroup first = new UserGroup();
        first.setId(1L);
        first.setName("研发组");
        first.setCreatorId(7L);
        first.setStatus(0);
        UserGroup second = new UserGroup();
        second.setId(2L);
        second.setName("测试组");
        second.setCreatorId(8L);
        second.setStatus(0);
        when(userGroupService.listNotDeleted()).thenReturn(List.of(first, second));
        when(userService.getByIds(any())).thenReturn(List.of(user(7L, "admin"), user(8L, "zhangsan")));
        when(userGroupService.getMemberCount(1L)).thenReturn(2);
        when(userGroupService.getMemberCount(2L)).thenReturn(5);

        mockMvc.perform(get("/api/user-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("研发组"))
                .andExpect(jsonPath("$[0].creatorName").value("admin"))
                .andExpect(jsonPath("$[0].memberCount").value(2))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].creatorName").value("zhangsan"))
                .andExpect(jsonPath("$[1].memberCount").value(5));

        // 列表路径复用批量查询结果，不再对每个组逐个 getById（N+1）
        verify(userService, never()).getById(any());
    }

    @Test
    void addMember_invokesServiceAndReturns200() throws Exception {
        mockMvc.perform(post("/api/user-groups/1/members")
                        .param("userId", "5")
                        .principal(login(7L)))
                .andExpect(status().isOk());

        verify(userGroupService).addMember(1L, 5L);
    }

    @Test
    void removeMember_invokesServiceAndReturns200() throws Exception {
        mockMvc.perform(delete("/api/user-groups/1/members/5"))
                .andExpect(status().isOk());

        verify(userGroupService).removeMember(1L, 5L);
    }

    @Test
    void getMembers_returnsUserVOsWithAccountMapping() throws Exception {
        User member = new User();
        member.setId(5L);
        member.setAccount("zhangcs");
        member.setUsername("张三");
        when(userGroupService.getMembers(1L)).thenReturn(List.of(member));

        mockMvc.perform(get("/api/user-groups/1/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(5))
                .andExpect(jsonPath("$[0].username").value("zhangcs"))
                .andExpect(jsonPath("$[0].realName").value("张三"));
    }

    @Test
    void getMyGroups_returnsGroupsOfCurrentUser() throws Exception {
        UserGroup group = new UserGroup();
        group.setId(1L);
        group.setName("研发组");
        group.setCreatorId(7L);
        group.setStatus(0);
        when(userGroupService.getByUser(7L)).thenReturn(List.of(group));

        mockMvc.perform(get("/api/user-groups/my")
                        .principal(login(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("研发组"))
                .andExpect(jsonPath("$[0].creatorId").value(7));
    }

    @Test
    void searchGroups_withKeyword_returnsMatchedGroups() throws Exception {
        UserGroup group = new UserGroup();
        group.setId(2L);
        group.setName("开发组");
        group.setDescription("研发人员用户组");
        group.setMemberCount(3);
        when(userGroupService.getByKeyword("开发")).thenReturn(List.of(group));

        mockMvc.perform(get("/api/user-groups/search").param("keyword", "开发"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(2))
                .andExpect(jsonPath("$[0].name").value("开发组"))
                .andExpect(jsonPath("$[0].memberCount").value(3));
    }

    @Test
    void searchGroups_withoutKeyword_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/user-groups/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
