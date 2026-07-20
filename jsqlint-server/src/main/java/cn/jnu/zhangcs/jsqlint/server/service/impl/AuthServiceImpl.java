package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.enums.Role;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.config.JwtConfig;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.model.vo.LoginVO;
import cn.jnu.zhangcs.jsqlint.server.security.JwtService;
import cn.jnu.zhangcs.jsqlint.server.service.AuthService;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtConfig jwtConfig;

    @Override
    public LoginVO login(String account, String password) {
        User user = userService.getByAccount(account);
        if (user == null) {
            throw new BizException("用户不存在");
        }

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BizException("密码错误");
        }

        if (user.getStatus() == 0) {
            throw new BizException("用户已被禁用");
        }

        Role role = user.getRoleEnum();

        String token = jwtService.generateToken(user.getAccount(), user.getId(), role.getValue());

        return LoginVO.builder()
                .id(user.getId())
                .token(token)
                .username(user.getAccount())
                .realName(user.getUsername())
                .role(role.getValue())
                .roleDescription(role.getDesc())
                .expiresIn(jwtConfig.getExpiration())
                .build();
    }

    @Override
    public void register(String account, String password, String realName, String email, String phone) {
        User existUser = userService.getByAccount( account);

        if (existUser != null) {
            throw new BizException("用户名已存在");
        }

        User user = new User();
        user.setAccount(account);
        user.setPassword(passwordEncoder.encode(password));
        user.setUsername(realName);
        user.setEmail(email);
        user.setPhone(phone);
        user.setStatus(0);
        user.setRole(Role.USER.getValue());

        userService.create(user);
    }

}