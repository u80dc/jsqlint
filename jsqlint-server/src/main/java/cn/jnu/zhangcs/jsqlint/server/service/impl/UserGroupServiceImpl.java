package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.entity.UserGroup;
import cn.jnu.zhangcs.jsqlint.server.entity.UserGroupMember;
import cn.jnu.zhangcs.jsqlint.server.mapper.UserGroupMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.UserGroupMemberMapper;
import cn.jnu.zhangcs.jsqlint.server.service.UserGroupService;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserGroupServiceImpl implements UserGroupService {

    private final UserGroupMapper userGroupMapper;

    private final UserGroupMemberMapper userGroupMemberMapper;

    private final UserService userService;

    @Override
    @Transactional
    public UserGroup create(UserGroup userGroup) {
        userGroup.setStatus(0);
        userGroupMapper.insert(userGroup);

        // 创建者默认加入用户组
        if (userGroup.getCreatorId() != null) {
            UserGroupMember relation = new UserGroupMember();
            relation.setUserId(userGroup.getCreatorId());
            relation.setUserGroupId(userGroup.getId());
            userGroupMemberMapper.insert(relation);
        }

        return userGroup;
    }

    @Override
    @Transactional
    public UserGroup update(UserGroup userGroup) {
        UserGroup exist = userGroupMapper.selectById(userGroup.getId());
        if (exist == null) {
            throw new BizException("用户组不存在");
        }
        userGroupMapper.updateById(userGroup);
        return userGroupMapper.selectById(userGroup.getId());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        UserGroup userGroup = userGroupMapper.selectById(id);
        if (userGroup == null) {
            throw new BizException("用户组不存在");
        }
        userGroup.setStatus(-1);
        userGroupMapper.updateById(userGroup);

        // 删除用户组成员关系
        userGroupMemberMapper.delete(
                new LambdaQueryWrapper<>(UserGroupMember.class)
                        .eq(UserGroupMember::getUserGroupId, id));
    }

    @Override
    public UserGroup getById(Long id) {
        return userGroupMapper.selectById(id);
    }

    @Override
    public List<UserGroup> list() {
        return userGroupMapper.selectList(new LambdaQueryWrapper<>(UserGroup.class)
                .orderByDesc(UserGroup::getCreateTime));
    }

    @Override
    public List<UserGroup> listNotDeleted() {
        return userGroupMapper.selectList(new LambdaQueryWrapper<>(UserGroup.class)
                .eq(UserGroup::getStatus, 0)
                .orderByDesc(UserGroup::getCreateTime));
    }

    @Override
    public @NonNull List<UserGroup> getByKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }

        keyword = "%" + keyword + "%";
        LambdaQueryWrapper<UserGroup> params = Wrappers.<UserGroup>lambdaQuery()
                .eq(UserGroup::getStatus, 0)
                .like(UserGroup::getName, keyword);
        Page<UserGroup> pages = userGroupMapper.selectPage(
                new Page<>(0, 20),
                params);
        return pages.getRecords();
    }

    @Override
    public @NonNull List<User> getMembers(Long id) {
        if (id == null) {
            return List.of();
        }
        LambdaQueryWrapper<UserGroupMember> query = Wrappers.<UserGroupMember>lambdaQuery().eq(UserGroupMember::getUserGroupId, id);
        List<UserGroupMember> relations = userGroupMemberMapper.selectList(query);
        if (relations.isEmpty()) {
            return List.of();
        }

        List<Long> userIds = relations.stream().map(UserGroupMember::getUserId).toList();
        if (CollectionUtils.isEmpty(userIds)) {
            return List.of();
        }
        return userService.getByIds(userIds);
    }

    @Override
    public int getMemberCount(Long id) {
        if (id == null) {
            return 0;
        }

        Long count = userGroupMemberMapper.selectCount(
                Wrappers.<UserGroupMember>lambdaQuery()
                        .eq(UserGroupMember::getUserGroupId, id)
        );
        if (count == null) {
            return 0;
        }
        return count.intValue();
    }

    @Override
    public void addMember(Long id, Long userId) {
        UserGroup userGroup = getById(id);
        if (userGroup == null || userGroup.getStatus() != 0) {
            throw new BizException("用户组不存在或已删除");
        }

        User user = userService.getById(userId);
        if (user == null) {
            throw new BizException("用户不存在");
        }

        UserGroupMember existRelation = userGroupMemberMapper.selectOne(Wrappers.<UserGroupMember>lambdaQuery().eq(UserGroupMember::getUserId, userId).eq(UserGroupMember::getUserGroupId, id));
        if (existRelation != null) {
            throw new BizException("用户已在该用户组中");
        }

        // 先从原用户组移除
        UserGroupMember currentRelation = userGroupMemberMapper.selectOne(Wrappers.<UserGroupMember>lambdaQuery().eq(UserGroupMember::getUserId, userId));

        if (currentRelation != null) {
            userGroupMemberMapper.deleteById(currentRelation.getId());
        }

        // 添加到新用户组
        UserGroupMember relation = new UserGroupMember();
        relation.setUserId(userId);
        relation.setUserGroupId(id);
        userGroupMemberMapper.insert(relation);
    }

    @Override
    public @NonNull List<UserGroup> getByUser(Long userId) {
        List<UserGroupMember> relations = userGroupMemberMapper.selectList(Wrappers.<UserGroupMember>lambdaQuery().eq(UserGroupMember::getUserId, userId));
        if (relations.isEmpty()) {
            return List.of();
        }

        List<Long> groupIds = relations.stream().map(UserGroupMember::getUserGroupId).toList();
        if (groupIds.isEmpty()) {
            return List.of();
        }
        return userGroupMapper.selectByIds(groupIds);
    }

    @Override
    public void removeMember(Long id, Long userId) {
        UserGroupMember relation = userGroupMemberMapper.selectOne(
                Wrappers.<UserGroupMember>lambdaQuery()
                        .eq(UserGroupMember::getUserId, userId)
                        .eq(UserGroupMember::getUserGroupId, id));
        if (relation == null) {
            throw new BizException("用户不在该用户组中");
        }

        userGroupMemberMapper.deleteById(relation.getId());
    }

}