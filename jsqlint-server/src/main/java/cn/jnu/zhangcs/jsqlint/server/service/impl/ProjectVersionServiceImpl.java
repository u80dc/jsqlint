package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStatus;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersion;
import cn.jnu.zhangcs.jsqlint.server.mapper.ProjectVersionMapper;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectVersionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectVersionServiceImpl implements ProjectVersionService {

    private final ProjectVersionMapper mapper;

    @Override
    public void delete(Long projectId) {
        if (projectId == null) {
            return;
        }
        LambdaQueryWrapper<ProjectVersion> query = Wrappers.<ProjectVersion>lambdaQuery()
                .eq(ProjectVersion::getProjectId, projectId);
        mapper.delete(query);
    }

    @Override
    public List<ProjectVersion> getList(Long projectId) {
        return getList(projectId, null);
    }

    @Override
    public List<ProjectVersion> getList(Long projectId, @Nullable ProjectVersionStatus status) {
        if (projectId == null) {
            return List.of();
        }
        LambdaQueryWrapper<ProjectVersion> query = Wrappers.lambdaQuery();
        query.eq(ProjectVersion::getProjectId, projectId);
        if (status != null) {
            query.eq(ProjectVersion::getOverallStatus, status.getCode());
        }
        query.orderByDesc(ProjectVersion::getOperationTime);
        return mapper.selectList(query);
    }

    @Override
    public void add(@NonNull ProjectVersion version) {
        mapper.insert(version);
    }

    @Override
    public @Nullable ProjectVersion getLast(Long projectId) {
        if (projectId == null) {
            return null;
        }
        LambdaQueryWrapper<ProjectVersion> query = Wrappers.<ProjectVersion>lambdaQuery()
                .eq(ProjectVersion::getProjectId, projectId)
                .orderByDesc(ProjectVersion::getOperationTime)
                .last("LIMIT 1");
        return mapper.selectOne(query);
    }

    @Override
    public @Nullable ProjectVersion getById(Long id) {
        if (id == null) {
            return null;
        }
        return mapper.selectById(id);
    }

    @Override
    public void update(@NonNull Long id, ProjectVersionStatus status, String message) {
        ProjectVersion version = getById(id);
        if (version == null) {
            throw new BizException("版本不存在");
        }
        // 更新状态
        version.setOverallStatus(status);
        // 更新操作时间
        version.setOperationTime(LocalDateTime.now());
        if (StringUtils.hasText(message)) {
            version.setMessage(message);
        }
        mapper.updateById(version);
    }

    @Override
    public void updateById(@NonNull Long id, ProjectVersion version) {
        if (version == null) {
            return;
        }
        version.setId(id);
        mapper.updateById(version);
    }

}
