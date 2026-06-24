package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.mapper.UserMapper;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Nonnull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
@Service
public class UserServiceImpl implements UserService {

    private final UserMapper mapper;

    @Override
    public @Nullable User getByAccount(String account) {
        if (!StringUtils.hasText(account)) {
            return null;
        }
        LambdaQueryWrapper<User> query = Wrappers.<User>lambdaQuery()
                .eq(User::getAccount, account);
        return mapper.selectOne(query);
    }

    @Override
    public @Nullable User getById(Long userId) {
        if (userId == null) {
            return null;
        }
        return mapper.selectById(userId);
    }

    @Override
    public @Nonnull List<User> getByIds(Set<Long> userIds) {
        if (CollectionUtils.isEmpty(userIds)) {
            return Collections.emptyList();
        }
        return mapper.selectByIds(userIds);
    }

}
