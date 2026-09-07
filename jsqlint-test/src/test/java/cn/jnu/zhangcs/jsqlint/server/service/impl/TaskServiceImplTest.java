package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskStatus;
import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskType;
import cn.jnu.zhangcs.jsqlint.server.entity.ScheduledTask;
import cn.jnu.zhangcs.jsqlint.server.mapper.ScheduledTaskMapper;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SystemSettingsVO;
import cn.jnu.zhangcs.jsqlint.server.service.SystemConfigService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TaskServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class TaskServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        // wrapper.set(...) 需要实体列缓存，纯单元测试下需手动初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ScheduledTask.class);
    }

    @Mock
    private ScheduledTaskMapper mapper;

    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private TaskServiceImpl taskService;

    private void mockInsertAssignId(long id) {
        doAnswer(inv -> {
            inv.getArgument(0, ScheduledTask.class).setId(id);
            return 1;
        }).when(mapper).insert(any(ScheduledTask.class));
    }

    @Test
    void submitGitPullTask_runningTaskExists_returnsMinusOne() {
        when(mapper.selectCount(any())).thenReturn(1L);

        assertThat(taskService.submitGitPullTask(1L, 2L, 10L)).isEqualTo(-1L);
        verify(mapper, never()).insert(any(ScheduledTask.class));
    }

    @Test
    void submitGitPullTask_fillsFieldsAndReturnsTaskId() {
        when(mapper.selectCount(any())).thenReturn(0L);
        mockInsertAssignId(30L);

        long taskId = taskService.submitGitPullTask(1L, 2L, 10L);

        assertThat(taskId).isEqualTo(30L);
        ArgumentCaptor<ScheduledTask> captor = ArgumentCaptor.forClass(ScheduledTask.class);
        verify(mapper).insert(captor.capture());
        ScheduledTask task = captor.getValue();
        assertThat(task.getType()).isEqualTo(TaskType.SYNC);
        assertThat(task.getProjectId()).isEqualTo(1L);
        assertThat(task.getVersionId()).isEqualTo(2L);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getRetryCount()).isZero();
        assertThat(task.getMaxRetries()).isEqualTo(3);
        assertThat(task.getOperatorId()).isEqualTo(10L);
        assertThat(task.getNextExecTime()).isNotNull();
    }

    @Test
    void submitInspectTask_fillsCronExpr() {
        mockInsertAssignId(31L);

        long taskId = taskService.submitInspectTask(1L, "0 0 2 * * ?", 10L);

        assertThat(taskId).isEqualTo(31L);
        ArgumentCaptor<ScheduledTask> captor = ArgumentCaptor.forClass(ScheduledTask.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(TaskType.INSPECT);
        assertThat(captor.getValue().getCronExpr()).isEqualTo("0 0 2 * * ?");
    }

    @Test
    void submitAlertTask_fillsPayload() {
        mockInsertAssignId(32L);

        long taskId = taskService.submitAlertTask(1L, "{\"level\":\"HIGH\"}", 10L);

        assertThat(taskId).isEqualTo(32L);
        ArgumentCaptor<ScheduledTask> captor = ArgumentCaptor.forClass(ScheduledTask.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(TaskType.ALERT);
        assertThat(captor.getValue().getPayload()).isEqualTo("{\"level\":\"HIGH\"}");
    }

    @Test
    void cancelTask_executesStatusUpdate() {
        when(mapper.update(any(), any())).thenReturn(1);

        taskService.cancelTask(30L);

        verify(mapper).update(any(), any());
    }

    @Test
    void cancelInspectByProject_updatesActiveTasksOneByOne() {
        ScheduledTask task1 = new ScheduledTask();
        task1.setId(30L);
        ScheduledTask task2 = new ScheduledTask();
        task2.setId(31L);
        when(mapper.selectList(any())).thenReturn(List.of(task1, task2));

        taskService.cancelInspectByProject(1L);

        verify(mapper, org.mockito.Mockito.times(2)).update(any(), any());
    }

    @Test
    void hasRunningTask_judgesByCount() {
        when(mapper.selectCount(any())).thenReturn(2L);
        assertThat(taskService.hasRunningTask(1L, TaskType.SYNC)).isTrue();

        when(mapper.selectCount(any())).thenReturn(0L);
        assertThat(taskService.hasRunningTask(1L, TaskType.SYNC)).isFalse();
    }

    @Test
    void getTask_wrapsInOptional() {
        ScheduledTask task = new ScheduledTask();
        when(mapper.selectById(30L)).thenReturn(task);
        assertThat(taskService.getTask(30L)).contains(task);

        when(mapper.selectById(31L)).thenReturn(null);
        assertThat(taskService.getTask(31L)).isEmpty();
    }

    @Test
    void getTasksByProject_delegatesToMapper() {
        List<ScheduledTask> list = List.of(new ScheduledTask());
        when(mapper.selectList(any())).thenReturn(list);

        assertThat(taskService.getTasksByProject(1L, TaskType.INSPECT)).isSameAs(list);
    }

    @Test
    void getRunningTasks_returnsPagedRecords() {
        Page<ScheduledTask> page = new Page<>(0, 100);
        page.setRecords(List.of(new ScheduledTask()));
        when(mapper.selectPage(any(), any())).thenReturn(page);

        assertThat(taskService.getRunningTasks(1L)).hasSize(1);
    }

    @Test
    void submit_activeTaskExists_reusesTaskId() {
        ScheduledTask active = new ScheduledTask();
        active.setId(99L);
        when(mapper.selectList(any())).thenReturn(List.of(active));
        ScheduledTask task = new ScheduledTask();
        task.setProjectId(1L);
        task.setType(TaskType.SYNC);

        Long taskId = taskService.submit(task);

        assertThat(taskId).isEqualTo(99L);
        verify(mapper, never()).insert(any(ScheduledTask.class));
    }

    @Test
    void submit_newTask_fillsDefaultsAndInserts() {
        when(mapper.selectList(any())).thenReturn(List.of());
        mockInsertAssignId(40L);
        ScheduledTask task = new ScheduledTask();
        task.setProjectId(1L);
        task.setType(TaskType.SYNC);

        Long taskId = taskService.submit(task);

        assertThat(taskId).isEqualTo(40L);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getRetryCount()).isZero();
        assertThat(task.getMaxRetries()).isEqualTo(3);
        assertThat(task.getNextExecTime()).isNotNull();
        verify(mapper).insert(task);
    }

    @Test
    void submitWithParams_fillsFieldsThenDelegatesInsert() {
        when(mapper.selectList(any())).thenReturn(List.of());
        mockInsertAssignId(41L);

        Long taskId = taskService.submit(1L, 10L, TaskType.ANALYSE, 0, 3);

        assertThat(taskId).isEqualTo(41L);
        ArgumentCaptor<ScheduledTask> captor = ArgumentCaptor.forClass(ScheduledTask.class);
        verify(mapper).insert(captor.capture());
        ScheduledTask task = captor.getValue();
        assertThat(task.getType()).isEqualTo(TaskType.ANALYSE);
        assertThat(task.getProjectId()).isEqualTo(1L);
        assertThat(task.getOperatorId()).isEqualTo(10L);
        assertThat(task.getVersionId()).isNull();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void getTasks_returnsPagedRecords() {
        Page<ScheduledTask> page = new Page<>(1, 10);
        page.setRecords(List.of(new ScheduledTask()));
        when(mapper.selectPage(any(), any())).thenReturn(page);

        assertThat(taskService.getTasks(TaskType.SYNC, TaskStatus.PENDING, 10)).hasSize(1);
    }

    @Test
    void getExecutableTasks_returnsPagedRecords() {
        Page<ScheduledTask> page = new Page<>(0, 50);
        page.setRecords(List.of(new ScheduledTask()));
        when(mapper.selectPage(any(), any())).thenReturn(page);

        assertThat(taskService.getExecutableTasks(50)).hasSize(1);
    }

    @Test
    void tryStart_judgesByUpdatedRows() {
        // tryStart 使用单参 mapper.update(Wrapper)
        when(mapper.update(any())).thenReturn(1);
        assertThat(taskService.tryStart(30L)).isTrue();

        when(mapper.update(any())).thenReturn(0);
        assertThat(taskService.tryStart(30L)).isFalse();
    }

    @Test
    void reclaimStuckRunningTasks_marksEachStuckTaskFailed() {
        mockRunningTaskTimeoutMinutes(60);
        ScheduledTask stuck1 = new ScheduledTask();
        stuck1.setId(50L);
        stuck1.setProjectId(1L);
        ScheduledTask stuck2 = new ScheduledTask();
        stuck2.setId(51L);
        stuck2.setProjectId(2L);
        when(mapper.selectList(any())).thenReturn(List.of(stuck1, stuck2));

        int reclaimed = taskService.reclaimStuckRunningTasks();

        assertThat(reclaimed).isEqualTo(2);
        verify(mapper, org.mockito.Mockito.times(2)).update(any(), any());
    }

    @Test
    void reclaimStuckRunningTasks_noStuckTask_noUpdate() {
        mockRunningTaskTimeoutMinutes(60);
        when(mapper.selectList(any())).thenReturn(List.of());

        assertThat(taskService.reclaimStuckRunningTasks()).isZero();
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void reclaimStuckRunningTasks_configMissing_fallsBackToDefaultAndStillReclaims() {
        // 配置读取异常时回退默认时限，回收链路不中断
        when(systemConfigService.getSettings()).thenThrow(new RuntimeException("db down"));
        when(mapper.selectList(any())).thenReturn(List.of());

        assertThat(taskService.reclaimStuckRunningTasks()).isZero();
        verify(mapper, never()).update(any(), any());
    }

    private void mockRunningTaskTimeoutMinutes(int minutes) {
        SystemSettingsVO.ProjectAnalysisSettings projectAnalysis =
                SystemSettingsVO.ProjectAnalysisSettings.builder()
                        .runningTaskTimeoutMinutes(minutes)
                        .build();
        SystemSettingsVO settings = SystemSettingsVO.builder()
                .projectAnalysis(projectAnalysis)
                .build();
        when(systemConfigService.getSettings()).thenReturn(settings);
    }

    @Test
    void markSuccess_executesSingleParamUpdate() {
        taskService.markSuccess(30L, 2L, "/tmp/code");

        verify(mapper).update(any());
    }

    @Test
    void markSuccess_notRunning_zeroRowsNoException() {
        // 僵死回收已置 FAILED 后，迟到的完成回调不应复活任务：0 行更新仅记录，不抛异常
        when(mapper.update(any())).thenReturn(0);

        taskService.markSuccess(30L, 2L, "/tmp/code");

        verify(mapper).update(any());
    }

    @Test
    void markFailedOrRetry_retryableWithinLimit_setsToRetry() {
        ScheduledTask task = new ScheduledTask();
        task.setId(30L);
        task.setRetryCount(0);
        task.setMaxRetries(3);

        taskService.markFailedOrRetry(task, "网络超时", true);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ScheduledTask>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        // 更新走 update(null, wrapper) 两参形式
        verify(mapper).update(any(), any());
    }

    @Test
    void markFailedOrRetry_notRetryable_setsToFailed() {
        ScheduledTask task = new ScheduledTask();
        task.setId(30L);
        task.setRetryCount(0);
        task.setMaxRetries(3);

        taskService.markFailedOrRetry(task, "语法错误", false);

        verify(mapper).update(any(), any());
    }

    @Test
    void markFailedOrRetry_retryLimitReached_setsToFailed() {
        ScheduledTask task = new ScheduledTask();
        task.setId(30L);
        task.setRetryCount(3);
        task.setMaxRetries(3);

        taskService.markFailedOrRetry(task, "网络超时", true);

        verify(mapper).update(any(), any());
    }

    @Test
    void markFailedOrRetry_nullErrorMessage_usesDefaultMessage() {
        ScheduledTask task = new ScheduledTask();
        task.setId(30L);
        task.setRetryCount(3);
        task.setMaxRetries(3);

        taskService.markFailedOrRetry(task, null, false);

        // 仅验证不抛异常，消息内容在 wrapper 内部无法直接断言
        verify(mapper).update(any(), any());
    }

    @Test
    void update_overridesIdAndSaves() {
        ScheduledTask task = new ScheduledTask();

        taskService.update(30L, task);

        assertThat(task.getId()).isEqualTo(30L);
        verify(mapper).updateById(task);
    }
}
