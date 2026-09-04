package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskType;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.service.AnalysisService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import cn.jnu.zhangcs.jsqlint.server.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Slf4j
@Service
public class AnalysisServiceImpl implements AnalysisService {

    private final TaskService taskService;

    private final ProjectService projectService;

    @Override
    public void sync(Long projectId, Long userId) {
        // 基础检查
        checkRole(projectId, userId);
        // 检查项目状态
        boolean isSyncing = projectService.isSyncing(projectId);
        if (isSyncing) {
            throw new BizException("项目同步中，请稍后");
        }

        // 提交任务
        Long taskId = taskService.submit(projectId, userId, TaskType.SYNC, 0, 3);
        log.info("同步任务已提交: projectId={}, userId={}, taskId={}", projectId, userId, taskId);
    }

    @Override
    public void compile(Long projectId, Long userId) {
        // 基础检查
        checkRole(projectId, userId);
        // 检查项目状态
        boolean isAlreadySync = projectService.isAlreadySync(projectId);
        if (!isAlreadySync) {
            throw new BizException("项目未同步");
        }

        // 提交任务
        Long taskId = taskService.submit(projectId, userId, TaskType.COMPILE, 0, 3);
        log.info("编译任务已提交: projectId={}, userId={}, taskId={}", projectId, userId, taskId);
    }

    @Override
    public void analyze(Long projectId, Long userId) {
        // 基础检查
        checkRole(projectId, userId);
        // 检查项目状态
        boolean isCompleted = projectService.isAlreadyCompiled(projectId);
        if (!isCompleted) {
            throw new BizException("项目未编译");
        }

        // 提交任务
        Long taskId = taskService.submit(projectId, userId, TaskType.ANALYSE, 0, 3);
        log.info("分析任务已提交: projectId={}, userId={}, taskId={}", projectId, userId, taskId);
    }

    @Override
    public void audit(Long projectId, Long userId) {
        // 基础检查
        checkRole(projectId, userId);
        // 检查项目状态
        boolean isAlreadyAnalysis = projectService.isAlreadyAnalysis(projectId);
        if (!isAlreadyAnalysis) {
            throw new BizException("项目未完成分析");
        }

        // 提交任务
        Long taskId = taskService.submit(projectId, userId, TaskType.AUDIT, 0, 3);
        log.info("审核任务已提交: projectId={}, userId={}, taskId={}", projectId, userId, taskId);
    }

    @Override
    public Long report(Long projectId, Long userId) {
        // 基础检查
        checkRole(projectId, userId);
        // 检查项目状态
        boolean isAlreadyAudit = projectService.isAlreadyAudit(projectId);
        if (!isAlreadyAudit) {
            throw new BizException("项目未完成审核");
        }

        // 提交任务
        Long taskId = taskService.submit(projectId, userId, TaskType.REPORT, 0, 3);
        log.info("报告生成任务已提交: projectId={}, userId={}, taskId={}", projectId, userId, taskId);
        return taskId;
    }

    @Override
    public boolean execute(Long projectId, Long userId) {
        checkRole(projectId, userId);
        // EXECUTE 串行执行完整流水线（同步→编译→分析→审核→报告），耗时长；
        // 已有活跃 EXECUTE 时拒绝重复提交，避免总览页堆积多条执行中任务
        if (taskService.hasRunningTask(projectId, TaskType.EXECUTE)) {
            throw new BizException("项目任务执行中，请稍后再试");
        }
        Long taskId = taskService.submit(projectId, userId, TaskType.EXECUTE, 0, 3);
        boolean submitted = taskId != null && taskId > 0;
        log.info("执行任务提交{}: projectId={}, userId={}, taskId={}",
                submitted ? "成功" : "失败", projectId, userId, taskId);
        return submitted;
    }

    /**
     * 检查权限
     *
     * @param projectId 项目
     * @param userId    用户
     */
    private void checkRole(Long projectId, Long userId) throws BizException {
        // 检查项目是否存在
        Project project = projectService.getById(projectId);
        if (project == null) {
            throw new BizException("项目不存在");
        }
        // 检查操作用户权限
        boolean isAdmin = projectService.isAdmin(projectId, userId);
        if (!isAdmin) {
            throw new BizException("该操作需要项目管理员权限");
        }
    }

}
