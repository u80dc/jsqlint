package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.converter.EntityConverter;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.config.JwtConfig;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.model.dto.ChangePasswordDTO;
import cn.jnu.zhangcs.jsqlint.server.model.dto.LoginDTO;
import cn.jnu.zhangcs.jsqlint.server.model.dto.RegisterDTO;
import cn.jnu.zhangcs.jsqlint.server.model.dto.UpdateProfileDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.LoginVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.UserVO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.AuthService;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "认证管理", description = "用户登录、注册、获取用户信息等认证相关接口")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserService userService;
    private final EntityConverter converter;
    private final JwtConfig jwtConfig;

    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "使用账号密码登录系统，返回JWT Token")
    public ResponseEntity<LoginVO> login(@Valid @RequestBody LoginDTO loginDTO) {
        LoginVO loginVO = authService.login(loginDTO.getUsername(), loginDTO.getPassword());
        loginVO.setExpiresIn(jwtConfig.getExpiration());
        return ResponseEntity.ok(loginVO);
    }

    @PostMapping("/register")
    @Operation(summary = "用户注册", description = "注册新用户账号")
    public ResponseEntity<String> register(@Valid @RequestBody RegisterDTO registerDTO) {
        authService.register(
                registerDTO.getUsername(),
                registerDTO.getPassword(),
                registerDTO.getRealName(),
                registerDTO.getEmail(),
                registerDTO.getPhone()
        );
        return ResponseEntity.ok("注册成功");
    }

    @GetMapping("/userinfo")
    @Operation(summary = "获取当前用户信息", description = "获取当前登录用户的详细信息")
    @Parameter(name = "authentication", hidden = true)
    public ResponseEntity<UserVO> getUserInfo(Authentication authentication) {
        // JwtAuthenticationFilter 以 LoginUser 作为 principal，getName() 会拿到其 toString，需取 username
        Object principal = authentication.getPrincipal();
        String username = principal instanceof LoginUser loginUser
                ? loginUser.getUsername()
                : authentication.getName();
        User user = userService.getByAccount(username);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        return ResponseEntity.ok(converter.toUserVO(user));
    }

    @PutMapping("/password")
    @Operation(summary = "修改密码", description = "校验旧密码后修改当前用户密码")
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody ChangePasswordDTO dto,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        userService.changePassword(loginUser.getUserId(), dto.getOldPassword(), dto.getNewPassword());
        return ResponseEntity.ok().build();
    }

    @PutMapping("/profile")
    @Operation(summary = "更新个人信息", description = "更新当前用户的姓名、邮箱、手机号")
    public ResponseEntity<UserVO> updateProfile(
            @Valid @RequestBody UpdateProfileDTO dto,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        User updated = userService.updateProfile(loginUser.getUserId(), dto.getRealName(), dto.getEmail(), dto.getPhone());
        return ResponseEntity.ok(converter.toUserVO(updated));
    }
}