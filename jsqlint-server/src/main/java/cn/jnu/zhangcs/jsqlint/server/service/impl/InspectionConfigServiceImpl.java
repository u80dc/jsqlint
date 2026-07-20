package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.InspectionConfig;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.mapper.InspectionConfigMapper;
import cn.jnu.zhangcs.jsqlint.server.service.InspectionConfigService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InspectionConfigServiceImpl implements InspectionConfigService {

    private static final String STATUS_DISABLED = "DISABLED";

    private final InspectionConfigMapper mapper;

    private final ProjectService projectService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InspectionConfig saveOrUpdate(Long projectId, String cronExpr, Boolean enabled, Long operatorId) {
        checkProjectAdmin(projectId, operatorId);
        Long nextExecTime = computeNextExecTime(cronExpr);
        InspectionConfig existing = getByProjectId(projectId);
        boolean enabledValue = enabled == null || enabled;

        if (existing == null) {
            InspectionConfig config = new InspectionConfig();
            config.setProjectId(projectId);
            config.setCronExpr(cronExpr);
            config.setEnabled(enabledValue ? 1 : 0);
            config.setNextExecTime(enabledValue ? nextExecTime : null);
            config.setLastStatus(enabledValue ? null : STATUS_DISABLED);
            config.setOperatorId(operatorId);
            mapper.insert(config);
            return config;
        }

        existing.setCronExpr(cronExpr);
        existing.setEnabled(enabledValue ? 1 : 0);
        existing.setNextExecTime(enabledValue ? nextExecTime : null);
        existing.setLastStatus(enabledValue ? enabledStatus(existing.getLastStatus()) : STATUS_DISABLED);
        existing.setOperatorId(operatorId);
        mapper.updateById(existing);
        return existing;
    }

    @Override
    public InspectionConfig getByProjectId(Long projectId) {
        LambdaQueryWrapper<InspectionConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(InspectionConfig::getProjectId, projectId);
        return mapper.selectOne(wrapper);
    }

    @Override
    public List<InspectionConfig> list(Boolean enabled) {
        LambdaQueryWrapper<InspectionConfig> wrapper = new LambdaQueryWrapper<>();
        if (enabled != null) {
            wrapper.eq(InspectionConfig::getEnabled, enabled ? 1 : 0);
        }
        wrapper.orderByDesc(InspectionConfig::getUpdateTime);
        List<InspectionConfig> configs = mapper.selectList(wrapper);
        fillProjectNames(configs);
        return configs;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setEnabled(Long projectId, boolean enabled, Long operatorId) {
        checkProjectAdmin(projectId, operatorId);
        InspectionConfig config = getByProjectId(projectId);
        if (config == null) {
            throw new BizException("巡检配置不存在");
        }
        config.setEnabled(enabled ? 1 : 0);
        config.setNextExecTime(enabled ? computeNextExecTime(config.getCronExpr()) : null);
        config.setLastStatus(enabled ? enabledStatus(config.getLastStatus()) : STATUS_DISABLED);
        config.setOperatorId(operatorId);
        mapper.updateById(config);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByProjectId(Long projectId, Long operatorId) {
        checkProjectAdmin(projectId, operatorId);
        LambdaQueryWrapper<InspectionConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(InspectionConfig::getProjectId, projectId);
        mapper.delete(wrapper);
    }

    @Override
    public List<InspectionConfig> findDueConfigs(int limit) {
        LambdaQueryWrapper<InspectionConfig> params = Wrappers.<InspectionConfig>lambdaQuery()
                .eq(InspectionConfig::getEnabled, 1)
                .isNotNull(InspectionConfig::getNextExecTime)
                .lt(InspectionConfig::getNextExecTime, System.currentTimeMillis())
                .orderByAsc(InspectionConfig::getNextExecTime);
        Page<InspectionConfig> page = mapper.selectPage(new Page<>(0, limit), params);
        return page.getRecords();
    }

    @Override
    public boolean tryClaimDue(Long configId) {
        LocalDateTime now = LocalDateTime.now();

        LambdaUpdateWrapper<InspectionConfig> wrapper = Wrappers.<InspectionConfig>lambdaUpdate()
                .set(InspectionConfig::getLastStatus, "RUNNING")
                .set(InspectionConfig::getLastExecTime, now)
                .set(InspectionConfig::getLastMessage, null)
                .set(InspectionConfig::getUpdateTime, now)
                .eq(InspectionConfig::getId, configId)
                .eq(InspectionConfig::getEnabled, 1)
                .isNotNull(InspectionConfig::getNextExecTime)
                .le(InspectionConfig::getNextExecTime, now)
                .and(w -> w.isNull(InspectionConfig::getLastStatus)
                        .or()
                        .ne(InspectionConfig::getLastStatus, "RUNNING"));
        return mapper.update(null, wrapper) > 0;
    }

    @Override
    public void scheduleNext(Long configId, String cronExpr) {
        LambdaUpdateWrapper<InspectionConfig> wrapper = Wrappers.<InspectionConfig>lambdaUpdate()
                .set(InspectionConfig::getNextExecTime, computeNextExecTime(cronExpr))
                .set(InspectionConfig::getUpdateTime, LocalDateTime.now())
                .eq(InspectionConfig::getId, configId);
        mapper.update(wrapper);
    }

    @Override
    public void markResult(Long projectId, String status, String message) {
        InspectionConfig config = getByProjectId(projectId);
        if (config != null) {
            LambdaUpdateWrapper<InspectionConfig> wrapper = Wrappers.<InspectionConfig>lambdaUpdate()
                    .set(InspectionConfig::getLastStatus, status)
                    .set(InspectionConfig::getLastMessage, truncate(message, 500))
                    .set(InspectionConfig::getUpdateTime, LocalDateTime.now())
                    .eq(InspectionConfig::getId, config.getId());
            mapper.update(wrapper);
        }
    }

    @Override
    public Long computeNextExecTime(String cronExpr) {
        try {
            CronExpression expression = CronExpression.parse(cronExpr);
            LocalDateTime next = expression.next(LocalDateTime.now());
            if (next == null) {
                throw new BizException("无法计算下一次巡检时间");
            }
            return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (IllegalArgumentException e) {
            throw new BizException("cron表达式不合法: " + e.getMessage());
        }
    }

    private void checkProjectAdmin(Long projectId, Long userId) {
        Project project = projectService.getById(projectId);
        if (project == null) {
            throw new BizException("项目不存在");
        }
        if (!projectService.isAdmin(projectId, userId)) {
            throw new BizException("该操作需要项目管理员权限");
        }
    }

    private void fillProjectNames(List<InspectionConfig> configs) {
        for (InspectionConfig config : configs) {
            Project project = projectService.getById(config.getProjectId());
            if (project != null) {
                config.setProjectName(project.getName());
            }
        }
    }

    private String truncate(String value, @SuppressWarnings("SameParameterValue") int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String enabledStatus(String currentStatus) {
        return STATUS_DISABLED.equals(currentStatus) ? null : currentStatus;
    }
}
