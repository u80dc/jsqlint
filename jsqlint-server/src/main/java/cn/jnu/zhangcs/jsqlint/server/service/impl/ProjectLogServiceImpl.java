package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectOperationType;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectLog;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.mapper.ProjectLogMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.ProjectMapper;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectLogService;
import cn.jnu.zhangcs.jsqlint.server.service.UserGroupService;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
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
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目日志服务实现
 *
 * @author zhangcs
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectLogServiceImpl implements ProjectLogService {

    private final ProjectLogMapper projectLogMapper;

    private final UserService userService;

    private final ProjectMapper projectMapper;

    private final UserGroupService userGroupService;

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void log(@NonNull Long projectId,
                    @NonNull ProjectOperationType operationType,
                    @NonNull Long operationUserId,
                    @NonNull String operationMessage,
                    @Nullable Long relatedId) {
        log(projectId, operationType, operationUserId, operationMessage, relatedId, null, null);
    }

    @Override
    public void log(@NonNull Long projectId,
                    @NonNull ProjectOperationType operationType,
                    @NonNull Long operationUserId,
                    @NonNull String operationMessage,
                    @Nullable Long relatedId,
                    @Nullable String ipAddress) {
        log(projectId, operationType, operationUserId, operationMessage, relatedId, ipAddress, null);
    }

    @Override
    public void log(@NonNull Long projectId,
                    @NonNull ProjectOperationType operationType,
                    @NonNull Long operationUserId,
                    @NonNull String operationMessage,
                    @Nullable Long relatedId,
                    @Nullable String ipAddress,
                    @Nullable String extraInfo) {
        try {
            ProjectLog projectLog = new ProjectLog();
            projectLog.setProjectId(projectId);
            projectLog.setOperationType(operationType.getValue());
            projectLog.setOperationUserId(operationUserId);

            // 获取用户名
            User user = userService.getById(operationUserId);
            if (user != null) {
                projectLog.setOperationUserName(user.getUsername());
            }

            if (ProjectOperationType.MEMBER_ADD == operationType ||
                    ProjectOperationType.MEMBER_REMOVE == operationType ||
                    ProjectOperationType.MEMBER_ROLE_UPDATE == operationType) {
                if (relatedId != null) {
                    User relatedUser = userService.getById(relatedId);
                    if (relatedUser != null) {
                        operationMessage = operationMessage.replaceAll(relatedId + "$", relatedUser.getUsername());
                    }
                } else if (operationMessage.contains(",")) {
                    String[] split = operationMessage.split(":");
                    String ids = split[1].replaceAll("\\s", "");
                    Set<Long> collect = Arrays.stream(ids.split(",")).map(Long::parseLong).collect(Collectors.toSet());
                    List<User> members = userService.getByIds(collect);
                    String usernames = members.stream().map(User::getUsername).collect(Collectors.joining(","));
                    operationMessage = split[0] + ": " + usernames;
                }
            } else if (ProjectOperationType.MEMBER_ADD_BY_GROUP == operationType) {
                List<User> members = userGroupService.getMembers(relatedId);
                String usernames = members.stream().map(User::getUsername).collect(Collectors.joining(","));
                operationMessage = operationMessage.replaceAll(relatedId + "$", usernames);
            }

            projectLog.setOperationMessage(operationMessage);
            projectLog.setRelatedId(relatedId);
            projectLog.setOperationTime(LocalDateTime.now());
            projectLog.setIpAddress(ipAddress);
            projectLog.setExtraInfo(extraInfo);

            projectLogMapper.insert(projectLog);
        } catch (Exception e) {
            log.error("记录项目日志失败: projectId={}, operationType={}, userId={}, message={}",
                    projectId, operationType.getValue(), operationUserId, operationMessage, e);
        }
    }

    @Override
    public @NonNull Page<ProjectLog> getPage(int page,
                                             int size,
                                             @Nullable Long projectId,
                                             @Nullable String operationType,
                                             @Nullable Long operationUserId,
                                             @Nullable String startTime,
                                             @Nullable String endTime) {
        LambdaQueryWrapper<ProjectLog> wrapper = new LambdaQueryWrapper<>();

        if (projectId != null) {
            wrapper.eq(ProjectLog::getProjectId, projectId);
        }

        if (StringUtils.hasText(operationType)) {
            wrapper.eq(ProjectLog::getOperationType, operationType);
        }

        if (operationUserId != null) {
            wrapper.eq(ProjectLog::getOperationUserId, operationUserId);
        }

        if (StringUtils.hasText(startTime)) {
            LocalDateTime start = LocalDateTime.parse(startTime, DATE_TIME_FORMATTER);
            wrapper.ge(ProjectLog::getOperationTime, start);
        }

        if (StringUtils.hasText(endTime)) {
            LocalDateTime end = LocalDateTime.parse(endTime, DATE_TIME_FORMATTER);
            wrapper.le(ProjectLog::getOperationTime, end);
        }

        wrapper.orderByDesc(ProjectLog::getOperationTime);

        Page<ProjectLog> result = projectLogMapper.selectPage(new Page<>(page, size), wrapper);

        // 填充项目名称
        List<ProjectLog> records = result.getRecords();
        for (ProjectLog log : records) {
            Project project = projectMapper.selectById(log.getProjectId());
            if (project != null) {
                log.setProjectName(project.getName());
            }
        }

        return result;
    }

}
