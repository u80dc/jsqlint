package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.entity.User;
import jakarta.annotation.Nonnull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * 用户接口
 *
 * @author zhangcs
 */
public interface UserService {

    /**
     * 通过账号获取用户信息
     *
     * @param account 姓名
     * @return 返回用户信息
     */
    @Nullable
    User getByAccount(String account);

    /**
     * 获取用户信息
     *
     * @param userId 用户唯一标识
     * @return 返回用户信息
     */
    @Nullable
    User getById(Long userId);

    /**
     * 获取用户信息
     *
     * @param userIds 用户唯一标识集合
     * @return 返回用户信息列表
     */
    @Nonnull
    List<User> getByIds(Set<Long> userIds);

}
