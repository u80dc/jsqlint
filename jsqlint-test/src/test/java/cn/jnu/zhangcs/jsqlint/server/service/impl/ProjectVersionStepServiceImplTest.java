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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ProjectVersionStepServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class ProjectVersionStepServiceImplTest {

    @Mock
    private ProjectVersionStepMapper mapper;

    @Mock
    private ProjectVersionService projectVersionService;

    @InjectMocks
    private ProjectVersionStepServiceImpl stepService;

    @Test
    void initializeSteps_insertsRecordsForAllFiveStages() {
        when(mapper.selectOne(any())).thenReturn(null);

        stepService.initializeSteps(1L, 2L);

        verify(mapper, times(ProjectVersionStepType.PROJECT_STEPS.size())).insert(any(ProjectVersionStep.class));
    }

    @Test
    void initializeSteps_existingStepsSkipped() {
        when(mapper.selectOne(any())).thenReturn(new ProjectVersionStep());

        stepService.initializeSteps(1L, 2L);

        verify(mapper, never()).insert(any(ProjectVersionStep.class));
    }

    @Test
    void markRunning_overwritesStatusAndTaskIdAndClearsTimes() {
        ProjectVersionStep step = new ProjectVersionStep();
        step.setStatus(ProjectVersionStepStatus.SUCCESS);
        step.setErrorMessage("旧错误");
        when(mapper.selectOne(any())).thenReturn(step);

        stepService.markRunning(1L, 2L, ProjectVersionStepType.SYNC_CODE, 30L);

        assertThat(step.getStatus()).isEqualTo(ProjectVersionStepStatus.RUNNING.getCode());
        assertThat(step.getTaskId()).isEqualTo(30L);
        assertThat(step.getErrorMessage()).isEmpty();
        assertThat(step.getStartTime()).isNotNull();
        assertThat(step.getEndTime()).isNull();
        verify(mapper).updateById(step);
        verify(mapper, never()).insert(any(ProjectVersionStep.class));
    }

    @Test
    void markRunning_stepNotFound_createsFirst() {
        when(mapper.selectOne(any())).thenReturn(null);

        stepService.markRunning(1L, 2L, ProjectVersionStepType.SYNC_CODE, 30L);

        verify(mapper).insert(any(ProjectVersionStep.class));
        verify(mapper).updateById(any(ProjectVersionStep.class));
    }

    @Test
    void markSuccess_updatesStatusAndEndTime() {
        ProjectVersionStep step = new ProjectVersionStep();
        step.setStatus(ProjectVersionStepStatus.RUNNING.getCode());
        when(mapper.selectOne(any())).thenReturn(step);

        stepService.markSuccess(1L, 2L, ProjectVersionStepType.COMPILE_CHECK, 31L);

        assertThat(step.getStatus()).isEqualTo(ProjectVersionStepStatus.SUCCESS.getCode());
        assertThat(step.getTaskId()).isEqualTo(31L);
        assertThat(step.getErrorMessage()).isEmpty();
        assertThat(step.getStartTime()).isNotNull();
        assertThat(step.getEndTime()).isNotNull();
        verify(mapper).updateById(step);
    }

    @Test
    void markFailed_errorMessageOver1000CharsTruncated() {
        ProjectVersionStep step = new ProjectVersionStep();
        when(mapper.selectOne(any())).thenReturn(step);
        String longMessage = "x".repeat(1500);

        stepService.markFailed(1L, 2L, ProjectVersionStepType.COMPILE_CHECK, 31L, longMessage);

        assertThat(step.getStatus()).isEqualTo(ProjectVersionStepStatus.FAILED.getCode());
        assertThat(step.getErrorMessage()).hasSize(1000);
        assertThat(step.getEndTime()).isNotNull();
    }

    @Test
    void isStepSuccess_judgesByStatus() {
        ProjectVersionStep step = new ProjectVersionStep();
        step.setStatus(ProjectVersionStepStatus.SUCCESS.getCode());
        when(mapper.selectOne(any())).thenReturn(step);
        assertThat(stepService.isStepSuccess(2L, ProjectVersionStepType.SYNC_CODE)).isTrue();

        when(mapper.selectOne(any())).thenReturn(null);
        assertThat(stepService.isStepSuccess(2L, ProjectVersionStepType.SYNC_CODE)).isFalse();
    }

    @Test
    void listByVersion_delegatesToMapper() {
        List<ProjectVersionStep> list = List.of(new ProjectVersionStep());
        when(mapper.selectList(any())).thenReturn(list);

        assertThat(stepService.listByVersion(2L)).isSameAs(list);
    }

    @Test
    void deleteByProjectId_delegatesToMapper() {
        stepService.deleteByProjectId(1L);
        verify(mapper).delete(any());
    }

    @Test
    void getProjectStatus_noVersion_returnsDefaultNotStartedView() {
        when(projectVersionService.getLast(1L)).thenReturn(null);

        ProjectStatusVO vo = stepService.getProjectStatus(1L);

        assertThat(vo.getProjectId()).isEqualTo(1L);
        assertThat(vo.getVersionId()).isNull();
        assertThat(vo.getOverallStatus()).isEqualTo(ProjectVersionStatus.NOT_STARTED.getCode());
        assertThat(vo.getOverallStatusName()).isEqualTo("未开始");
        assertThat(vo.getSteps()).hasSize(5);
        assertThat(vo.getSteps()).allSatisfy(s -> {
            assertThat(s.getStatus()).isEqualTo(ProjectVersionStepStatus.NOT_STARTED.getCode());
            assertThat(s.getCompleted()).isZero();
        });
    }

    @Test
    void getProjectStatus_withVersion_fillsFiveStepsInOrder() {
        ProjectVersion version = new ProjectVersion();
        version.setId(2L);
        version.setOverallStatus(ProjectVersionStatus.RUNNING.getCode());
        version.setCurrentStep(ProjectVersionStepType.SYNC_CODE.getCode());
        version.setMessage("同步中");
        when(projectVersionService.getLast(1L)).thenReturn(version);

        ProjectVersionStep syncStep = new ProjectVersionStep();
        syncStep.setId(11L);
        syncStep.setStepType(ProjectVersionStepType.SYNC_CODE.getCode());
        syncStep.setStatus(ProjectVersionStepStatus.SUCCESS.getCode());
        syncStep.setTaskId(30L);
        // 仅同步步骤存在，其余四个步骤走默认未开始
        when(mapper.selectList(any())).thenReturn(List.of(syncStep));

        ProjectStatusVO vo = stepService.getProjectStatus(1L);

        assertThat(vo.getVersionId()).isEqualTo(2L);
        assertThat(vo.getOverallStatus()).isEqualTo(ProjectVersionStatus.RUNNING.getCode());
        assertThat(vo.getCurrentStepName()).isEqualTo("同步代码");
        assertThat(vo.getMessage()).isEqualTo("同步中");
        assertThat(vo.getSteps()).hasSize(5);
        assertThat(vo.getSteps()).isSortedAccordingTo(java.util.Comparator.comparing(ProjectVersionStepVO::getStepOrder));
        ProjectVersionStepVO first = vo.getSteps().get(0);
        assertThat(first.getStepCode()).isEqualTo("SYNC_CODE");
        assertThat(first.getStatusName()).isEqualTo("执行成功");
        assertThat(first.getCompleted()).isEqualTo(1);
        assertThat(first.getTaskId()).isEqualTo(30L);
    }

    @Test
    void getProjectStatus_unSupportStepNameIsNull() {
        ProjectVersion version = new ProjectVersion();
        version.setId(2L);
        version.setOverallStatus(ProjectVersionStatus.SUCCESS.getCode());
        version.setCurrentStep(ProjectVersionStepType.UN_SUPPORT.getCode());
        when(projectVersionService.getLast(1L)).thenReturn(version);
        when(mapper.selectList(any())).thenReturn(List.of());

        ProjectStatusVO vo = stepService.getProjectStatus(1L);

        assertThat(vo.getCurrentStepName()).isNull();
    }
}
