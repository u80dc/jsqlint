package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.config.JwtConfig;
import cn.jnu.zhangcs.jsqlint.server.common.converter.EntityConverter;
import cn.jnu.zhangcs.jsqlint.server.common.exception.GlobalExceptionHandler;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.model.vo.LoginVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.UserVO;
import cn.jnu.zhangcs.jsqlint.server.service.AuthService;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
                .build();
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
    void getUserInfo_bizExceptionMappedTo400() throws Exception {
        when(userService.getByAccount("admin")).thenThrow(new RuntimeException("boom"));

        Authentication authentication = new UsernamePasswordAuthenticationToken("admin", "password");

        mockMvc.perform(get("/api/auth/userinfo").principal(authentication))
                .andExpect(status().isInternalServerError());
    }
}
