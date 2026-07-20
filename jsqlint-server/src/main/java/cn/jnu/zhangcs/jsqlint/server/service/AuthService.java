package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.model.vo.LoginVO;

/**
 * 认证服务接口
 * <p>提供用户登录、注册等认证相关能力。
 *
 * @author zhangcs
 */
public interface AuthService {

    /**
     * 用户登录
     *
     * @param account  账号
     * @param password 密码
     * @return 登录结果（含 JWT 令牌）
     */
    LoginVO login(String account, String password);

    /**
     * 用户注册（注册后默认处于待启用状态，需管理员启用）
     *
     * @param username 账号
     * @param password 密码
     * @param realName 真实姓名
     * @param email    邮箱
     * @param phone    手机号
     */
    void register(String username, String password, String realName, String email, String phone);
}