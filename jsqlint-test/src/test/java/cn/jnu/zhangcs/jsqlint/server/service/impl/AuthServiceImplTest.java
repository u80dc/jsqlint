package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.enums.Role;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.config.JwtConfig;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.model.vo.LoginVO;
import cn.jnu.zhangcs.jsqlint.server.security.JwtService;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuthServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserService userService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private JwtConfig jwtConfig;

    @InjectMocks
    private AuthServiceImpl authService;

    private User buildUser() {
        User user = new User();
        user.setId(1L);
        user.setAccount("zhangcs");
        user.setPassword("encoded");
        user.setUsername("张三");
        user.setStatus(1);
        user.setRole("ROLE_ADMIN");
        return user;
    }

    @Test
    void login_success_returnsTokenAndRoleInfo() {
        User user = buildUser();
        when(userService.getByAccount("zhangcs")).thenReturn(user);
        when(passwordEncoder.matches("raw123", "encoded")).thenReturn(true);
        when(jwtService.generateToken("zhangcs", 1L, "ROLE_ADMIN")).thenReturn("token-abc");
        when(jwtConfig.getExpiration()).thenReturn(3600_000L);

        LoginVO vo = authService.login("zhangcs", "raw123");

        assertThat(vo.getId()).isEqualTo(1L);
        assertThat(vo.getToken()).isEqualTo("token-abc");
        assertThat(vo.getUsername()).isEqualTo("zhangcs");
        assertThat(vo.getRealName()).isEqualTo("张三");
        assertThat(vo.getRole()).isEqualTo("ROLE_ADMIN");
        assertThat(vo.getRoleDescription()).isEqualTo("系统管理员");
        assertThat(vo.getExpiresIn()).isEqualTo(3600_000L);
    }

    @Test
    void login_accountNotFound_throwsException() {
        when(userService.getByAccount("nobody")).thenReturn(null);

        assertThatThrownBy(() -> authService.login("nobody", "pwd"))
                .isInstanceOf(BizException.class)
                .hasMessage("用户不存在");
    }

    @Test
    void login_passwordMismatch_throwsException() {
        when(userService.getByAccount("zhangcs")).thenReturn(buildUser());
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

        assertThatThrownBy(() -> authService.login("zhangcs", "wrong"))
                .isInstanceOf(BizException.class)
                .hasMessage("密码错误");
    }

    @Test
    void login_userDisabled_throwsException() {
        User user = buildUser();
        user.setStatus(0);
        when(userService.getByAccount("zhangcs")).thenReturn(user);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> authService.login("zhangcs", "raw123"))
                .isInstanceOf(BizException.class)
                .hasMessage("用户已被禁用");
    }

    @Test
    void register_success_passwordEncodedAndStatusDisabled() {
        when(userService.getByAccount("newuser")).thenReturn(null);
        when(passwordEncoder.encode("raw123")).thenReturn("encoded-new");

        authService.register("newuser", "raw123", "李四", "lisi@x.com", "13800000000");

        ArgumentCaptor<User> captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userService).create(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getAccount()).isEqualTo("newuser");
        assertThat(saved.getPassword()).isEqualTo("encoded-new");
        assertThat(saved.getUsername()).isEqualTo("李四");
        assertThat(saved.getEmail()).isEqualTo("lisi@x.com");
        assertThat(saved.getPhone()).isEqualTo("13800000000");
        assertThat(saved.getStatus()).isZero();
        assertThat(saved.getRole()).isEqualTo(Role.USER.getValue());
    }

    @Test
    void register_duplicateAccount_throwsException() {
        when(userService.getByAccount("zhangcs")).thenReturn(buildUser());

        assertThatThrownBy(() -> authService.register("zhangcs", "pwd", "张三", null, null))
                .isInstanceOf(BizException.class)
                .hasMessage("用户名已存在");
    }
}
