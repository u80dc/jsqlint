package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.converter.EntityConverter;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.entity.UserGroup;
import cn.jnu.zhangcs.jsqlint.server.model.dto.UserCreateDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.UserVO;
import cn.jnu.zhangcs.jsqlint.server.service.UserGroupService;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AdminUserController} 单元测试（standalone MockMvc，service/converter 全部 Mock）。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class AdminUserControllerTest {

    @Mock
    private EntityConverter converter;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserGroupService userGroupService;

    @Mock
    private UserService userService;

    @InjectMocks
    private AdminUserController adminUserController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(adminUserController)
                .setControllerAdvice(new GlobalExceptionHandler())
                // standalone 默认 StringHttpMessageConverter 为 ISO-8859-1，中文响应会丢字，需显式指定 UTF-8
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new JacksonJsonHttpMessageConverter())
                .build();
    }

    private User user(Long id, String account) {
        User user = new User();
        user.setId(id);
        user.setAccount(account);
        user.setUsername("用户" + id);
        user.setStatus(1);
        return user;
    }

    @Test
    void createUser_validBody_encodesPasswordAndReturnsMessage() throws Exception {
        User user = user(null, "newuser");
        when(converter.fromCreateDTO(any(UserCreateDTO.class)))
                .thenReturn(user);
        when(passwordEncoder.encode("123456")).thenReturn("encoded-pwd");

        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"username\":\"newuser\",\"password\":\"123456\","
                                + "\"realName\":\"张三\",\"email\":\"a@b.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("用户创建成功"));

        // 密码应经 encoder 加密后再落库
        assertEquals("encoded-pwd", user.getPassword());
        verify(userService).create(user);
    }

    @Test
    void updateUser_withPassword_encodesAndSaves() throws Exception {
        User existing = user(3L, "tom");
        when(userService.getById(3L)).thenReturn(existing);
        when(passwordEncoder.encode("newpass")).thenReturn("encoded-new");

        mockMvc.perform(put("/api/admin/users/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"realName\":\"汤姆\",\"password\":\"newpass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("用户更新成功"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).updateById(eq(3L), captor.capture());
        assertEquals("encoded-new", captor.getValue().getPassword());
    }

    @Test
    void updateUser_withoutPassword_keepsOriginalPassword() throws Exception {
        User existing = user(3L, "tom");
        existing.setPassword("original-pwd");
        when(userService.getById(3L)).thenReturn(existing);

        mockMvc.perform(put("/api/admin/users/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"realName\":\"汤姆\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("用户更新成功"));

        // 未提供密码时不应触发加密，保留原密码
        verify(passwordEncoder, never()).encode(any());
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).updateById(eq(3L), captor.capture());
        assertEquals("original-pwd", captor.getValue().getPassword());
    }

    @Test
    void updateUser_notFound_returns400() throws Exception {
        when(userService.getById(99L)).thenReturn(null);

        mockMvc.perform(put("/api/admin/users/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"realName\":\"汤姆\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("用户不存在"));
    }

    @Test
    void deleteUser_existing_returnsMessage() throws Exception {
        mockMvc.perform(delete("/api/admin/users/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("用户删除成功"));

        verify(userService).delete(3L);
    }

    @Test
    void listUsers_withFilters_returnsPagedGroupedVOList() throws Exception {
        User tom = user(2L, "tom");
        UserVO tomVO = UserVO.builder().id(2L).username("tom").realName("汤姆").status(1).build();
        Page<User> page = new Page<>(1, 10);
        page.setRecords(List.of(tom));
        page.setTotal(1);
        when(userService.getUserPage("tom", null, null, null, 1, 1, 10)).thenReturn(page);
        when(converter.toUserVO(tom)).thenReturn(tomVO);

        UserGroup group = new UserGroup();
        group.setId(5L);
        group.setName("研发组");
        when(userGroupService.getByUser(2L)).thenReturn(List.of(group));

        mockMvc.perform(get("/api/admin/users")
                        .param("username", "tom")
                        .param("status", "1")
                        .param("page", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list.length()").value(1))
                .andExpect(jsonPath("$.list[0].id").value(2))
                .andExpect(jsonPath("$.list[0].username").value("tom"))
                .andExpect(jsonPath("$.list[0].groups.length()").value(1))
                .andExpect(jsonPath("$.list[0].groups[0].id").value(5))
                .andExpect(jsonPath("$.list[0].groups[0].name").value("研发组"));

        verify(userService).getUserPage("tom", null, null, null, 1, 1, 10);
    }

    @Test
    void listUsers_defaultPaging_appliesDefaults() throws Exception {
        Page<User> page = new Page<>(1, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        when(userService.getUserPage(null, null, null, null, null, 1, 10)).thenReturn(page);

        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.list.length()").value(0));

        verify(userService).getUserPage(null, null, null, null, null, 1, 10);
    }

    @Test
    void getUserById_existing_returnsUserVO() throws Exception {
        User tom = user(3L, "tom");
        when(userService.getById(3L)).thenReturn(tom);
        when(converter.toUserVO(tom)).thenReturn(UserVO.builder()
                .id(3L).username("tom").realName("汤姆").status(1).build());

        mockMvc.perform(get("/api/admin/users/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.username").value("tom"))
                .andExpect(jsonPath("$.realName").value("汤姆"));
    }

    @Test
    void setUserEnabled_true_callsEnableAndReturnsMessage() throws Exception {
        mockMvc.perform(patch("/api/admin/users/3/enabled").param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("用户启用成功"));

        verify(userService).enable(3L);
        verify(userService, never()).disable(any());
    }

    @Test
    void setUserEnabled_false_callsDisableAndReturnsMessage() throws Exception {
        mockMvc.perform(patch("/api/admin/users/3/enabled").param("enabled", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("用户禁用成功"));

        verify(userService).disable(3L);
        verify(userService, never()).enable(any());
    }

    @Test
    void setUserGroups_withGroupId_addsMembership() throws Exception {
        mockMvc.perform(put("/api/admin/users/9/groups").param("groupId", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("用户组设置成功"));

        verify(userGroupService).addMember(3L, 9L);
        verify(userGroupService, never()).removeMember(any(), any());
    }

    @Test
    void setUserGroups_withoutGroupId_removesAllMemberships() throws Exception {
        UserGroup first = new UserGroup();
        first.setId(2L);
        first.setName("研发组");
        UserGroup second = new UserGroup();
        second.setId(4L);
        second.setName("测试组");
        when(userGroupService.getByUser(9L)).thenReturn(List.of(first, second));

        mockMvc.perform(put("/api/admin/users/9/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("用户组设置成功"));

        // groupId 为空表示移除该用户的所有用户组
        verify(userGroupService).removeMember(2L, 9L);
        verify(userGroupService).removeMember(4L, 9L);
        verify(userGroupService, never()).addMember(any(), any());
    }
}
