package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStatus;
import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStepStatus;
import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStepType;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersion;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersionStep;
import cn.jnu.zhangcs.jsqlint.server.mapper.ProjectVersionStepMapper;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectStatusVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectVersionStepVO;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectVersionService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectVersionStepService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProjectVersionStepServiceImpl implements ProjectVersionStepService {

    private final ProjectVersionStepMapper mapper;

    private final ProjectVersionService projectVersionService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initializeSteps(@NonNull Long projectId, @NonNull Long versionId) {
        for (ProjectVersionStepType stepType : ProjectVersionStepType.PROJECT_STEPS) {
            ProjectVersionStep step = getStep(versionId, stepType);
            if (step != null) {
                continue;
            }
            step = new ProjectVersionStep();
            step.setProjectId(projectId);
            step.setVersionId(versionId);
            step.setStepType(stepType);
            step.setStatus(ProjectVersionStepStatus.NOT_STARTED);
            mapper.insert(step);
        }
    }

    @Override
    public void markRunning(@NonNull Long projectId,
                            @NonNull Long versionId,
                            @NonNull ProjectVersionStepType stepType,
                            Long taskId) {
        ProjectVersionStep step = ensureStep(projectId, versionId, stepType);
        step.setStatus(ProjectVersionStepStatus.RUNNING);
        step.setTaskId(taskId);
        step.setErrorMessage("");
        step.setStartTime(LocalDateTime.now());
        step.setEndTime(null);
        mapper.updateById(step);
    }

    @Override
    public void markSuccess(@NonNull Long projectId,
                            @NonNull Long versionId,
                            @NonNull ProjectVersionStepType stepType,
                            Long taskId) {
        ProjectVersionStep step = ensureStep(projectId, versionId, stepType);
        step.setStatus(ProjectVersionStepStatus.SUCCESS);
        step.setTaskId(taskId);
        step.setErrorMessage("");
        if (step.getStartTime() == null) {
            step.setStartTime(LocalDateTime.now());
        }
        step.setEndTime(LocalDateTime.now());
        mapper.updateById(step);
    }

    @Override
    public void markFailed(@NonNull Long projectId,
                           @NonNull Long versionId,
                           @NonNull ProjectVersionStepType stepType,
                           Long taskId,
                           String errorMessage) {
        ProjectVersionStep step = ensureStep(projectId, versionId, stepType);
        step.setStatus(ProjectVersionStepStatus.FAILED);
        step.setTaskId(taskId);
        step.setErrorMessage(truncate(errorMessage, 1000));
        if (step.getStartTime() == null) {
            step.setStartTime(LocalDateTime.now());
        }
        step.setEndTime(LocalDateTime.now());
        mapper.updateById(step);
    }

    @Override
    public boolean isStepSuccess(@NonNull Long versionId, @NonNull ProjectVersionStepType stepType) {
        ProjectVersionStep step = getStep(versionId, stepType);
        return step != null && ProjectVersionStepStatus.from(step.getStatus()) == ProjectVersionStepStatus.SUCCESS;
    }

    @Override
    public List<ProjectVersionStep> listByVersion(@NonNull Long versionId) {
        return mapper.selectList(Wrappers.<ProjectVersionStep>lambdaQuery()
                .eq(ProjectVersionStep::getVersionId, versionId)
                .orderByAsc(ProjectVersionStep::getStepOrder));
    }

    @Override
    public void deleteByProjectId(@NonNull Long projectId) {
        mapper.delete(Wrappers.<ProjectVersionStep>lambdaQuery()
                .eq(ProjectVersionStep::getProjectId, projectId));
    }

    @Override
    public ProjectStatusVO getProjectStatus(@NonNull Long projectId) {
        ProjectVersion version = projectVersionService.getLast(projectId);
        if (version == null) {
            return ProjectStatusVO.builder()
                    .projectId(projectId)
                    .overallStatus(ProjectVersionStatus.NOT_STARTED.getCode())
                    .overallStatusName(ProjectVersionStatus.NOT_STARTED.getDesc())
                    .steps(defaultStepVOs(projectId, null))
                    .build();
        }

        List<ProjectVersionStepVO> steps = toStepVOs(projectId, version.getId(), listByVersion(version.getId()));
        ProjectVersionStatus overallStatus = ProjectVersionStatus.from(version.getOverallStatus());
        ProjectVersionStepType currentStep = ProjectVersionStepType.from(version.getCurrentStep());
        return ProjectStatusVO.builder()
                .projectId(projectId)
                .versionId(version.getId())
                .overallStatus(version.getOverallStatus())
                .overallStatusName(overallStatus.getDesc())
                .currentStep(version.getCurrentStep())
                .currentStepName(currentStep == ProjectVersionStepType.UN_SUPPORT ? null : currentStep.getDesc())
                .message(version.getMessage())
                .steps(steps)
                .build();
    }

    private List<ProjectVersionStepVO> toStepVOs(Long projectId, Long versionId, List<ProjectVersionStep> steps) {
        Map<Integer, ProjectVersionStep> stepIndex = steps.stream()
                .collect(Collectors.toMap(ProjectVersionStep::getStepType, step -> step, (left, right) -> right));
        return ProjectVersionStepType.PROJECT_STEPS.stream()
                .map(stepType -> toStepVO(projectId, versionId, stepType, stepIndex.get(stepType.getCode())))
                .sorted(Comparator.comparing(ProjectVersionStepVO::getStepOrder))
                .toList();
    }

    private List<ProjectVersionStepVO> defaultStepVOs(Long projectId, Long versionId) {
        return ProjectVersionStepType.PROJECT_STEPS.stream()
                .map(stepType -> toStepVO(projectId, versionId, stepType, null))
                .toList();
    }

    private ProjectVersionStepVO toStepVO(Long projectId,
                                          Long versionId,
                                          ProjectVersionStepType stepType,
                                          ProjectVersionStep step) {
        ProjectVersionStepStatus status = step == null
                ? ProjectVersionStepStatus.NOT_STARTED
                : ProjectVersionStepStatus.from(step.getStatus());
        return ProjectVersionStepVO.builder()
                .id(step == null ? null : step.getId())
                .projectId(projectId)
                .versionId(versionId)
                .stepType(stepType.getCode())
                .stepCode(stepType.name())
                .stepName(stepType.getDesc())
                .stepOrder(stepType.getStepOrder())
                .status(status.getCode())
                .statusName(status.getDesc())
                .completed(status == ProjectVersionStepStatus.SUCCESS ? 1 : 0)
                .taskId(step == null ? null : step.getTaskId())
                .errorMessage(step == null ? null : step.getErrorMessage())
                .startTime(step == null ? null : step.getStartTime())
                .endTime(step == null ? null : step.getEndTime())
                .build();
    }

    private ProjectVersionStep ensureStep(Long projectId, Long versionId, ProjectVersionStepType stepType) {
        ProjectVersionStep step = getStep(versionId, stepType);
        if (step != null) {
            return step;
        }
        step = new ProjectVersionStep();
        step.setProjectId(projectId);
        step.setVersionId(versionId);
        step.setStepType(stepType);
        step.setStatus(ProjectVersionStepStatus.NOT_STARTED);
        mapper.insert(step);
        return step;
    }

    private ProjectVersionStep getStep(Long versionId, ProjectVersionStepType stepType) {
        return mapper.selectOne(Wrappers.<ProjectVersionStep>lambdaQuery()
                .eq(ProjectVersionStep::getVersionId, versionId)
                .eq(ProjectVersionStep::getStepType, stepType.getCode()));
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
