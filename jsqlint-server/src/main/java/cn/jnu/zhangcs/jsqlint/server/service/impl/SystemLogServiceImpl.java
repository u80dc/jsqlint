package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.enums.SystemOperationType;
import cn.jnu.zhangcs.jsqlint.server.entity.SystemLog;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.mapper.SystemLogMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.UserMapper;
import cn.jnu.zhangcs.jsqlint.server.service.SystemLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 系统日志服务实现
 *
 * @author zhangcs
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemLogServiceImpl implements SystemLogService {

    private final SystemLogMapper systemLogMapper;

    private final UserMapper userMapper;

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void log(@NonNull SystemOperationType type,
                    @NonNull Long userId,
                    @NonNull String message,
                    @Nullable Long relatedId) {
        log(type, userId, message, relatedId, null, null);
    }

    @Override
    public void log(@NonNull SystemOperationType type,
                    @NonNull Long userId,
                    @NonNull String message,
                    @Nullable Long relatedId,
                    @Nullable String ipAddress) {
        log(type, userId, message, relatedId, ipAddress, null);
    }

    @Override
    public void log(@NonNull SystemOperationType type,
                    @NonNull Long userId,
                    @NonNull String message,
                    @Nullable Long relatedId,
                    @Nullable String ipAddress,
                    @Nullable String extraInfo) {
        try {
            SystemLog systemLog = new SystemLog();
            systemLog.setOperationType(type.getValue());
            systemLog.setOperationUserId(userId);

            // 获取用户名
            User user = userMapper.selectById(userId);
            if (user != null) {
                systemLog.setOperationUserName(user.getUsername());
            }

            systemLog.setOperationMessage(message);
            systemLog.setRelatedId(relatedId);
            systemLog.setOperationTime(LocalDateTime.now());
            systemLog.setIpAddress(ipAddress);
            systemLog.setExtraInfo(extraInfo);

            systemLogMapper.insert(systemLog);
        } catch (Exception e) {
            log.error("记录系统日志失败: operationType={}, userId={}, message={}",
                    type.getValue(), userId, message, e);
        }
    }

    @Override
    public @NonNull Page<SystemLog> getPage(int page,
                                            int size,
                                            @Nullable String type,
                                            @Nullable Long userId,
                                            @Nullable String startTime,
                                            @Nullable String endTime) {
        LambdaQueryWrapper<SystemLog> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(type)) {
            wrapper.eq(SystemLog::getOperationType, type);
        }

        if (userId != null) {
            wrapper.eq(SystemLog::getOperationUserId, userId);
        }

        if (StringUtils.hasText(startTime)) {
            LocalDateTime start = LocalDateTime.parse(startTime, DATE_TIME_FORMATTER);
            wrapper.ge(SystemLog::getOperationTime, start);
        }

        if (StringUtils.hasText(endTime)) {
            LocalDateTime end = LocalDateTime.parse(endTime, DATE_TIME_FORMATTER);
            wrapper.le(SystemLog::getOperationTime, end);
        }

        wrapper.orderByDesc(SystemLog::getOperationTime);

        return systemLogMapper.selectPage(new Page<>(page, size), wrapper);
    }

    @Override
    public @NonNull Page<SystemLog> getByRelatedId(int page, int size, @NonNull Long relatedId) {
        LambdaQueryWrapper<SystemLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SystemLog::getRelatedId, relatedId);
        wrapper.orderByDesc(SystemLog::getOperationTime);

        return systemLogMapper.selectPage(new Page<>(page, size), wrapper);
    }
}
