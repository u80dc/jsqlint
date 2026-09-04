package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.annotation.SystemLog;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.MavenRepository;
import cn.jnu.zhangcs.jsqlint.server.mapper.MavenRepositoryMapper;
import cn.jnu.zhangcs.jsqlint.server.service.MavenRepositoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Maven 仓库服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MavenRepositoryServiceImpl implements MavenRepositoryService {

    private final MavenRepositoryMapper repositoryMapper;

    @Override
    @SystemLog(
            operationType = "REPOSITORY_CREATE",
            message = "创建仓库: #{#result.name} (#{#result.repoId})",
            relatedId = "#result.id",
            includeResult = true)
    public MavenRepository create(MavenRepository repository) {
        // 校验 repoId 唯一性
        LambdaQueryWrapper<MavenRepository> queryWrapper = new LambdaQueryWrapper<MavenRepository>()
                .eq(MavenRepository::getRepoId, repository.getRepoId());
        if (repositoryMapper.selectCount(queryWrapper) > 0) {
            throw new BizException("仓库ID已存在: " + repository.getRepoId());
        }

        // 设置默认值
        if (!StringUtils.hasText(repository.getLayout())) {
            repository.setLayout("default");
        }
        if (repository.getReleasesEnabled() == null) {
            repository.setReleasesEnabled(true);
        }
        if (!StringUtils.hasText(repository.getReleasesUpdatePolicy())) {
            repository.setReleasesUpdatePolicy("daily");
        }
        if (!StringUtils.hasText(repository.getReleasesChecksumPolicy())) {
            repository.setReleasesChecksumPolicy("fail");
        }
        if (repository.getSnapshotsEnabled() == null) {
            repository.setSnapshotsEnabled(false);
        }
        if (!StringUtils.hasText(repository.getSnapshotsUpdatePolicy())) {
            repository.setSnapshotsUpdatePolicy("daily");
        }
        if (!StringUtils.hasText(repository.getSnapshotsChecksumPolicy())) {
            repository.setSnapshotsChecksumPolicy("fail");
        }

        repositoryMapper.insert(repository);
        return repository;
    }

    @Override
    @SystemLog(
            operationType = "REPOSITORY_UPDATE",
            message = "更新仓库: #{#result.name} (#{#result.repoId})",
            relatedId = "#repository.id",
            includeResult = true)
    public MavenRepository update(MavenRepository repository) {
        MavenRepository existing = repositoryMapper.selectById(repository.getId());
        if (existing == null) {
            throw new BizException("仓库不存在");
        }

        // 校验 repoId 唯一性（排除自己）
        LambdaQueryWrapper<MavenRepository> queryWrapper = new LambdaQueryWrapper<MavenRepository>()
                .eq(MavenRepository::getRepoId, repository.getRepoId())
                .ne(MavenRepository::getId, repository.getId());
        if (repositoryMapper.selectCount(queryWrapper) > 0) {
            throw new BizException("仓库ID已存在: " + repository.getRepoId());
        }

        repositoryMapper.updateById(repository);
        return repositoryMapper.selectById(repository.getId());
    }

    @Override
    @SystemLog(
            operationType = "REPOSITORY_DELETE",
            message = "删除仓库: #{#id}",
            relatedId = "#id")
    public void delete(Long id) {
        repositoryMapper.deleteById(id);
    }

    @Override
    public MavenRepository getById(Long id) {
        return repositoryMapper.selectById(id);
    }

    @Override
    public List<MavenRepository> list(@Nullable Integer status) {
        return getRepositoryPage(null, status, 1, Integer.MAX_VALUE).getRecords();
    }

    @Override
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<MavenRepository> getRepositoryPage(
            @Nullable Integer type, @Nullable Integer status, int page, int pageSize) {
        LambdaQueryWrapper<MavenRepository> query = Wrappers.lambdaQuery();
        if (status != null) {
            query.eq(MavenRepository::getStatus, status);
        }
        if (type != null) {
            query.eq(MavenRepository::getType, type);
        }
        query.orderByDesc(MavenRepository::getCreateTime);
        return repositoryMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, pageSize), query);
    }

    @Override
    public List<MavenRepository> getByType(Integer type, @Nullable Integer status) {
        if (type == null) {
            return List.of();
        }
        LambdaQueryWrapper<MavenRepository> query = Wrappers.lambdaQuery();
        if (status != null) {
            query.eq(MavenRepository::getStatus, status);
        }
        query.eq(MavenRepository::getType, type)
                .orderByDesc(MavenRepository::getCreateTime);
        return repositoryMapper.selectList(query);
    }
}