package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.config.JwtConfig;
import cn.jnu.zhangcs.jsqlint.server.common.converter.EntityConverter;
import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.model.vo.LoginVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.UserVO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.AuthService;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AuthController} 单元测试（standalone MockMvc，service 全部 Mock）。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    @Mock
    private UserService userService;

    @Mock
    private EntityConverter converter;

    @Mock
    private JwtConfig jwtConfig;

    @InjectMocks
    private AuthController authController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .setControllerAdvice(new GlobalExceptionHandler())
                // standalone 默认 StringHttpMessageConverter 为 ISO-8859-1，中文响应会丢字，需显式指定 UTF-8
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new JacksonJsonHttpMessageConverter())
                // standalone 默认不注册安全相关解析器，@AuthenticationPrincipal 需显式注册
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void login(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser("admin", userId), "password"));
    }

    @Test
    void login_validCredentials_returnsTokenWithExpiresIn() throws Exception {
        LoginVO loginVO = new LoginVO();
        loginVO.setToken("jwt-token");
        when(authService.login("admin", "123456")).thenReturn(loginVO);
        when(jwtConfig.getExpiration()).thenReturn(3600L);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.expiresIn").value(3600));
    }

    @Test
    void login_blankUsername_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数校验失败"));
    }

    @Test
    void register_validBody_returnsSuccessMessage() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"password\":\"123456\","
                                + "\"realName\":\"张三\",\"email\":\"a@b.com\",\"phone\":\"13800000000\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string("注册成功"));
    }

    @Test
    void register_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"password\":\"123456\","
                                + "\"realName\":\"张三\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数校验失败"))
                .andExpect(jsonPath("$.errors.email").value("邮箱格式不正确"));
    }

    @Test
    void getUserInfo_authenticated_returnsUserVO() throws Exception {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        when(userService.getByAccount("admin")).thenReturn(user);
        UserVO vo = new UserVO();
        vo.setId(1L);
        vo.setUsername("admin");
        when(converter.toUserVO(user)).thenReturn(vo);

        Authentication authentication = new UsernamePasswordAuthenticationToken("admin", "password");

        mockMvc.perform(get("/api/auth/userinfo").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.username").value("admin"));
    }

    @Test
    void getUserInfo_loginUserPrincipal_resolvesUsername() throws Exception {
        User user = new User();
        user.setId(1L);
        when(userService.getByAccount("admin")).thenReturn(user);
        UserVO vo = new UserVO();
        vo.setId(1L);
        vo.setUsername("admin");
        when(converter.toUserVO(user)).thenReturn(vo);
        // 模拟 JwtAuthenticationFilter 的行为：principal 为 LoginUser 而非字符串
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                new LoginUser("admin", 1L), "password");

        mockMvc.perform(get("/api/auth/userinfo").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void getUserInfo_userMissing_returns400() throws Exception {
        when(userService.getByAccount("admin")).thenReturn(null);
        Authentication authentication = new UsernamePasswordAuthenticationToken("admin", "password");

        mockMvc.perform(get("/api/auth/userinfo").principal(authentication))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changePassword_validBody_delegatesToService() throws Exception {
        login(1L);

        mockMvc.perform(put("/api/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"old123\",\"newPassword\":\"new123456\"}"))
                .andExpect(status().isOk());

        verify(userService).changePassword(1L, "old123", "new123456");
    }

    @Test
    void changePassword_shortNewPassword_returns400() throws Exception {
        login(1L);

        mockMvc.perform(put("/api/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"old123\",\"newPassword\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数校验失败"));

        verify(userService, org.mockito.Mockito.never())
                .changePassword(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void updateProfile_validBody_returnsUpdatedVo() throws Exception {
        login(1L);
        User updated = new User();
        updated.setId(1L);
        when(userService.updateProfile(1L, "张三", "a@b.com", "13800000000")).thenReturn(updated);
        UserVO vo = new UserVO();
        vo.setId(1L);
        vo.setRealName("张三");
        when(converter.toUserVO(updated)).thenReturn(vo);

        mockMvc.perform(put("/api/auth/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"realName\":\"张三\",\"email\":\"a@b.com\",\"phone\":\"13800000000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.realName").value("张三"));
    }

    @Test
    void updateProfile_invalidEmail_returns400() throws Exception {
        login(1L);

        mockMvc.perform(put("/api/auth/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").value("邮箱格式不正确"));
    }
}
