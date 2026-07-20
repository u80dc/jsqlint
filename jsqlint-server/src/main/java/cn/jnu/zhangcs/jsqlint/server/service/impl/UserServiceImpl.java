package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.annotation.SystemLog;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.mapper.UserMapper;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Nonnull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper mapper;

    @Override
    public @Nullable User getByAccount(String account) {
        if (!StringUtils.hasText(account)) {
            return null;
        }

        return mapper.selectOne(
                new LambdaQueryWrapper<>(User.class)
                        .eq(User::getAccount, account)
        );
    }

    @Override
    public @Nullable User getById(Long id) {
        if (id == null) {
            return null;
        }
        return mapper.selectById(id);
    }

    @Override
    @SystemLog(
            operationType = "USER_CREATE",
            message = "创建用户: #{#user.username} (账号: #{#user.account})",
            relatedId = "#user.id",
            includeResult = true)
    public void create(@NonNull User user) {
        String account = user.getAccount();
        if (!StringUtils.hasText(account)) {
            throw new BizException("账号不允许为空");
        }

        User exist = getByAccount(account);
        if (exist != null) {
            throw new BizException("账号名已存在");
        }
        mapper.insert(user);
    }

    @Override
    public @Nonnull List<User> getByIds(Collection<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return mapper.selectByIds(ids);
    }

    @Override
    public @NonNull List<User> getByKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }
        // query
        LambdaQueryWrapper<User> query = Wrappers.lambdaQuery();
        query.select(User::getId, User::getAccount, User::getUsername);
        query.eq(User::getStatus, 1);
        query.and(wr -> wr.like(User::getUsername, keyword)
                .or()
                .like(User::getAccount, keyword));
        // Limit
        Page<User> page = new Page<>(1, 20);
        Page<User> pageResult = mapper.selectPage(page, query);
        return pageResult.getRecords();
    }

    @Override
    @SystemLog(
            operationType = "USER_UPDATE",
            message = "更新用户: #{#id}",
            relatedId = "#id")
    public void updateById(@NonNull Long id, User user) {
        User exist = mapper.selectById(id);
        if (exist == null) {
            throw new BizException("用户不存在");
        }
        user.setId(id);
        mapper.updateById(user);
    }

    @Override
    @SystemLog(
            operationType = "USER_DELETE",
            message = "删除用户: #{#id}",
            relatedId = "#id")
    public void delete(Long id) {
        if (id == null) {
            return;
        }
        mapper.deleteById(id);
    }

    @Override
    public @NonNull List<User> getUserList(String account, String username, String phone, String email, Integer status) {
        LambdaQueryWrapper<User> query = new LambdaQueryWrapper<>();
        if (account != null && !account.isBlank()) {
            query.like(User::getAccount, account);
        }
        if (username != null && !username.isBlank()) {
            query.like(User::getUsername, username);
        }
        if (phone != null && !phone.isBlank()) {
            query.like(User::getPhone, phone);
        }
        if (email != null && !email.isBlank()) {
            query.like(User::getEmail, email);
        }
        if (status != null) {
            query.eq(User::getStatus, status);
        }
        query.orderByDesc(User::getCreateTime);
        return mapper.selectList(query);
    }

    @Override
    @Transactional
    @SystemLog(
            operationType = "USER_ENABLE",
            message = "启用用户: #{#id}",
            relatedId = "#id")
    public void enable(Long id) {
        User user = getById(id);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        user.setStatus(1);
        updateById(id, user);
    }

    @Override
    @Transactional
    @SystemLog(
            operationType = "USER_DISABLE",
            message = "禁用用户: #{#id}",
            relatedId = "#id")
    public void disable(Long id) {
        User user = getById(id);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        user.setStatus(0);
        updateById(id, user);
    }

}