package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.converter.EntityConverter;
import cn.jnu.zhangcs.jsqlint.server.config.JwtConfig;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.model.dto.LoginDTO;
import cn.jnu.zhangcs.jsqlint.server.model.dto.RegisterDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.LoginVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.UserVO;
import cn.jnu.zhangcs.jsqlint.server.service.AuthService;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
        String username = authentication.getName();
        User user = userService.getByAccount(username);
        return ResponseEntity.ok(converter.toUserVO(user));
    }
}