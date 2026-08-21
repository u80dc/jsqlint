package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskType;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import cn.jnu.zhangcs.jsqlint.server.service.TaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AnalysisServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceImplTest {

    @Mock
    private TaskService taskService;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private AnalysisServiceImpl analysisService;

    private void mockAdmin() {
        when(projectService.getById(1L)).thenReturn(new Project());
        when(projectService.isAdmin(1L, 10L)).thenReturn(true);
    }

    @Test
    void sync_projectNotFound_throwsException() {
        when(projectService.getById(9L)).thenReturn(null);

        assertThatThrownBy(() -> analysisService.sync(9L, 10L))
                .isInstanceOf(BizException.class)
                .hasMessage("项目不存在");
    }

    @Test
    void sync_notAdmin_throwsException() {
        when(projectService.getById(1L)).thenReturn(new Project());
        when(projectService.isAdmin(1L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> analysisService.sync(1L, 10L))
                .isInstanceOf(BizException.class)
                .hasMessage("该操作需要项目管理员权限");
    }

    @Test
    void sync_alreadySyncing_throwsException() {
        mockAdmin();
        when(projectService.isSyncing(1L)).thenReturn(true);

        assertThatThrownBy(() -> analysisService.sync(1L, 10L))
                .isInstanceOf(BizException.class)
                .hasMessage("项目同步中，请稍后");
        verify(taskService, never()).submit(1L, 10L, TaskType.SYNC, 0, 3);
    }

    @Test
    void sync_validRequest_submitsSyncTask() {
        mockAdmin();
        when(projectService.isSyncing(1L)).thenReturn(false);
        when(taskService.submit(1L, 10L, TaskType.SYNC, 0, 3)).thenReturn(30L);

        analysisService.sync(1L, 10L);

        verify(taskService).submit(1L, 10L, TaskType.SYNC, 0, 3);
    }

    @Test
    void compile_notSynced_throwsException() {
        mockAdmin();
        when(projectService.isAlreadySync(1L)).thenReturn(false);

        assertThatThrownBy(() -> analysisService.compile(1L, 10L))
                .isInstanceOf(BizException.class)
                .hasMessage("项目未同步");
    }

    @Test
    void compile_validRequest_submitsCompileTask() {
        mockAdmin();
        when(projectService.isAlreadySync(1L)).thenReturn(true);
        when(taskService.submit(1L, 10L, TaskType.COMPILE, 0, 3)).thenReturn(31L);

        analysisService.compile(1L, 10L);

        verify(taskService).submit(1L, 10L, TaskType.COMPILE, 0, 3);
    }

    @Test
    void analyze_notCompiled_throwsException() {
        mockAdmin();
        when(projectService.isAlreadyCompiled(1L)).thenReturn(false);

        assertThatThrownBy(() -> analysisService.analyze(1L, 10L))
                .isInstanceOf(BizException.class)
                .hasMessage("项目未编译");
    }

    @Test
    void analyze_validRequest_submitsAnalyseTask() {
        mockAdmin();
        when(projectService.isAlreadyCompiled(1L)).thenReturn(true);
        when(taskService.submit(1L, 10L, TaskType.ANALYSE, 0, 3)).thenReturn(32L);

        analysisService.analyze(1L, 10L);

        verify(taskService).submit(1L, 10L, TaskType.ANALYSE, 0, 3);
    }

    @Test
    void audit_analysisNotCompleted_throwsException() {
        mockAdmin();
        when(projectService.isAlreadyAnalysis(1L)).thenReturn(false);

        assertThatThrownBy(() -> analysisService.audit(1L, 10L))
                .isInstanceOf(BizException.class)
                .hasMessage("项目未完成分析");
    }

    @Test
    void audit_validRequest_submitsAuditTask() {
        mockAdmin();
        when(projectService.isAlreadyAnalysis(1L)).thenReturn(true);
        when(taskService.submit(1L, 10L, TaskType.AUDIT, 0, 3)).thenReturn(33L);

        analysisService.audit(1L, 10L);

        verify(taskService).submit(1L, 10L, TaskType.AUDIT, 0, 3);
    }

    @Test
    void report_auditNotCompleted_throwsException() {
        mockAdmin();
        when(projectService.isAlreadyAudit(1L)).thenReturn(false);

        assertThatThrownBy(() -> analysisService.report(1L, 10L))
                .isInstanceOf(BizException.class)
                .hasMessage("项目未完成审核");
    }

    @Test
    void report_validRequest_returnsTaskId() {
        mockAdmin();
        when(projectService.isAlreadyAudit(1L)).thenReturn(true);
        when(taskService.submit(1L, 10L, TaskType.REPORT, 0, 3)).thenReturn(34L);

        assertThat(analysisService.report(1L, 10L)).isEqualTo(34L);
    }

    @Test
    void execute_taskSubmitted_returnsTrue() {
        mockAdmin();
        when(taskService.submit(1L, 10L, TaskType.EXECUTE, 0, 3)).thenReturn(35L);
        assertThat(analysisService.execute(1L, 10L)).isTrue();
    }

    @Test
    void execute_negativeTaskId_returnsFalse() {
        mockAdmin();
        when(taskService.submit(1L, 10L, TaskType.EXECUTE, 0, 3)).thenReturn(-1L);
        assertThat(analysisService.execute(1L, 10L)).isFalse();
    }
}
