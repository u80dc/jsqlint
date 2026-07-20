package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.entity.User;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * 用户业务接口
 * <p>提供用户的查询、创建、更新、删除以及按关键字/条件检索等能力。
 *
 * @author zhangcs
 */
public interface UserService {

    /**
     * 根据账号查询用户
     *
     * @param account 账号
     * @return 用户信息；不存在时返回 null
     */
    @Nullable
    User getByAccount(String account);

    /**
     * 根据ID查询用户
     *
     * @param id 用户ID
     * @return 用户信息；不存在时返回 null
     */
    @Nullable
    User getById(Long id);

    /**
     * 创建用户
     *
     * @param user 用户信息
     */
    void create(@NonNull User user);

    /**
     * 根据ID批量查询用户
     *
     * @param ids 用户ID集合
     * @return 用户列表
     */
    @NonNull
    List<User> getByIds(Collection<Long> ids);

    /**
     * 通过关键字获取用户
     *
     * @param keyword 关键字
     * @return 返回用户列表
     */
    @NonNull
    List<User> getByKeyword(String keyword);

    /**
     * 根据ID更新用户
     *
     * @param id   用户ID
     * @param user 用户信息
     */
    void updateById(@NonNull Long id, User user);

    /**
     * 删除用户
     *
     * @param id 用户ID
     */
    void delete(Long id);

    /**
     * 获取用户
     *
     * @param account  账号
     * @param username 名称
     * @param phone    电话
     * @param email    邮箱
     * @param status   状态
     * @return 返回用户列表
     */
    @NonNull
    List<User> getUserList(String account, String username, String phone, String email, Integer status);

    /**
     * 修改用户状态为启用
     *
     * @param id 用户ID
     */
    void enable(Long id);

    /**
     * 修改用户状态为禁用
     *
     * @param id 用户ID
     */
    void disable(Long id);
}