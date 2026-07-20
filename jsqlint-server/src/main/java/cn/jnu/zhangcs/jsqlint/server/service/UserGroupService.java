package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.entity.UserGroup;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * 用户组业务接口
 *
 * @author zhangcs
 */
public interface UserGroupService {

    /**
     * 创建用户组
     *
     * @param userGroup 用户组
     * @return 返回用户组信息
     */
    UserGroup create(UserGroup userGroup);

    /**
     * 更新用户组
     *
     * @param userGroup 用户组信息
     * @return 更新后的用户组
     */
    UserGroup update(UserGroup userGroup);

    /**
     * 删除用户组（软删除，并清除成员关系）
     *
     * @param id 用户组ID
     */
    void delete(Long id);

    /**
     * 根据ID获取用户组
     *
     * @param id 用户组ID
     * @return 用户组信息
     */
    UserGroup getById(Long id);

    /**
     * 查询全部用户组
     *
     * @return 用户组列表
     */
    List<UserGroup> list();

    /**
     * 查询未删除的用户组列表
     *
     * @return 用户组列表
     */
    List<UserGroup> listNotDeleted();

    /**
     * 通过关键字查询用户组
     *
     * @param keyword 关键字
     * @return 返回用户组列表
     */
    @NonNull
    List<UserGroup> getByKeyword(String keyword);

    /**
     * 获取用户组成员
     *
     * @param id 用户组
     * @return 返回用户列表
     */
    @NonNull
    List<User> getMembers(Long id);

    /**
     * 获取用户组成员数量
     *
     * @param id 用户组
     * @return 返回数量
     */
    int getMemberCount(Long id);

    /**
     * 新增用户组成员
     *
     * @param id     用户组
     * @param userId 用户
     */
    void addMember(Long id, Long userId);

    /**
     * 根据用户获取用户组
     *
     * @param userId 用户
     * @return 返回用户组列表
     */
    @NonNull
    List<UserGroup> getByUser(Long userId);

    /**
     * 移除成员
     *
     * @param id     用户组
     * @param userId 用户
     */
    void removeMember(Long id, Long userId);
}