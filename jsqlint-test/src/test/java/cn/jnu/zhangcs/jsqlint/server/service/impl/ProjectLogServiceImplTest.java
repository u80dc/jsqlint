package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectOperationType;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectLog;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.mapper.ProjectLogMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.ProjectMapper;
import cn.jnu.zhangcs.jsqlint.server.service.UserGroupService;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ProjectLogServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class ProjectLogServiceImplTest {

    @Mock
    private ProjectLogMapper projectLogMapper;

    @Mock
    private UserService userService;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private UserGroupService userGroupService;

    @InjectMocks
    private ProjectLogServiceImpl projectLogService;

    @Test
    void log_fillsOperatorAndLogFields() {
        User operator = new User();
        operator.setId(10L);
        operator.setUsername("张三");
        when(userService.getById(10L)).thenReturn(operator);

        projectLogService.log(1L, ProjectOperationType.PROJECT_CREATE, 10L, "创建项目: 订单系统", null);

        ArgumentCaptor<ProjectLog> captor = ArgumentCaptor.forClass(ProjectLog.class);
        verify(projectLogMapper).insert(captor.capture());
        ProjectLog log = captor.getValue();
        assertThat(log.getProjectId()).isEqualTo(1L);
        assertThat(log.getOperationType()).isEqualTo("PROJECT_CREATE");
        assertThat(log.getOperationUserId()).isEqualTo(10L);
        assertThat(log.getOperationUserName()).isEqualTo("张三");
        assertThat(log.getOperationMessage()).isEqualTo("创建项目: 订单系统");
        assertThat(log.getOperationTime()).isNotNull();
    }

    @Test
    void log_operatorNotFound_nameIsNull() {
        when(userService.getById(10L)).thenReturn(null);

        projectLogService.log(1L, ProjectOperationType.PROJECT_CREATE, 10L, "创建项目", null);

        ArgumentCaptor<ProjectLog> captor = ArgumentCaptor.forClass(ProjectLog.class);
        verify(projectLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getOperationUserName()).isNull();
    }

    @Test
    void log_memberOperation_replacesTrailingUserIdWithUserName() {
        User operator = new User();
        operator.setId(10L);
        User related = new User();
        related.setId(20L);
        related.setUsername("李四");
        when(userService.getById(10L)).thenReturn(operator);
        when(userService.getById(20L)).thenReturn(related);

        projectLogService.log(1L, ProjectOperationType.MEMBER_ADD, 10L, "添加项目成员: 20", 20L);

        ArgumentCaptor<ProjectLog> captor = ArgumentCaptor.forClass(ProjectLog.class);
        verify(projectLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getOperationMessage()).isEqualTo("添加项目成员: 李四");
        assertThat(captor.getValue().getRelatedId()).isEqualTo(20L);
    }

    @Test
    void log_memberOperationMultipleUsers_replacesIdListWithUserNames() {
        User operator = new User();
        operator.setId(10L);
        when(userService.getById(10L)).thenReturn(operator);
        User u1 = new User();
        u1.setId(20L);
        u1.setUsername("李四");
        User u2 = new User();
        u2.setId(21L);
        u2.setUsername("王五");
        // relatedId 为 null 且消息含逗号时按 "前缀: id,id" 解析
        when(userService.getByIds(any())).thenReturn(List.of(u1, u2));

        projectLogService.log(1L, ProjectOperationType.MEMBER_ADD, 10L, "添加项目成员: 20, 21", null);

        ArgumentCaptor<ProjectLog> captor = ArgumentCaptor.forClass(ProjectLog.class);
        verify(projectLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getOperationMessage()).isEqualTo("添加项目成员: 李四,王五");
    }

    @Test
    void log_addMembersByGroup_replacesGroupIdWithGroupUserNames() {
        User operator = new User();
        operator.setId(10L);
        when(userService.getById(10L)).thenReturn(operator);
        User u1 = new User();
        u1.setUsername("李四");
        when(userGroupService.getMembers(5L)).thenReturn(List.of(u1));

        projectLogService.log(1L, ProjectOperationType.MEMBER_ADD_BY_GROUP, 10L, "添加项目成员: 5", 5L);

        ArgumentCaptor<ProjectLog> captor = ArgumentCaptor.forClass(ProjectLog.class);
        verify(projectLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getOperationMessage()).isEqualTo("添加项目成员: 李四");
    }

    @Test
    void log_internalExceptionSwallowed_doesNotAffectMainFlow() {
        when(userService.getById(10L)).thenThrow(new RuntimeException("数据库不可用"));

        assertThatNoException().isThrownBy(() ->
                projectLogService.log(1L, ProjectOperationType.PROJECT_CREATE, 10L, "创建项目", null));

        verify(projectLogMapper, never()).insert(any(ProjectLog.class));
    }

    @Test
    void getPage_fillsProjectName() {
        ProjectLog record = new ProjectLog();
        record.setProjectId(1L);
        Page<ProjectLog> page = new Page<>(1, 10);
        page.setRecords(List.of(record));
        when(projectLogMapper.selectPage(any(), any())).thenReturn(page);
        Project project = new Project();
        project.setName("订单系统");
        when(projectMapper.selectById(1L)).thenReturn(project);

        Page<ProjectLog> result = projectLogService.getPage(1, 10, 1L, null, null, null, null);

        assertThat(result.getRecords().get(0).getProjectName()).isEqualTo("订单系统");
    }

    @Test
    void getPage_projectNotFound_nameStaysNull() {
        ProjectLog record = new ProjectLog();
        record.setProjectId(1L);
        Page<ProjectLog> page = new Page<>(1, 10);
        page.setRecords(List.of(record));
        when(projectLogMapper.selectPage(any(), any())).thenReturn(page);
        when(projectMapper.selectById(1L)).thenReturn(null);

        Page<ProjectLog> result = projectLogService.getPage(1, 10, 1L, null, null, null, null);

        assertThat(result.getRecords().get(0).getProjectName()).isNull();
    }

    @Test
    void getByProjectId_fillsProjectName() {
        ProjectLog record = new ProjectLog();
        record.setProjectId(1L);
        Page<ProjectLog> page = new Page<>(1, 10);
        page.setRecords(List.of(record));
        when(projectLogMapper.selectPage(any(), any())).thenReturn(page);
        Project project = new Project();
        project.setName("订单系统");
        when(projectMapper.selectById(1L)).thenReturn(project);

        Page<ProjectLog> result = projectLogService.getByProjectId(1, 10, 1L);

        assertThat(result.getRecords().get(0).getProjectName()).isEqualTo("订单系统");
    }

    @Test
    void getByRelatedId_fillsProjectName() {
        ProjectLog record = new ProjectLog();
        record.setProjectId(1L);
        Page<ProjectLog> page = new Page<>(1, 10);
        page.setRecords(List.of(record));
        when(projectLogMapper.selectPage(any(), any())).thenReturn(page);
        when(projectMapper.selectById(1L)).thenReturn(new Project());

        Page<ProjectLog> result = projectLogService.getByRelatedId(1, 10, 1L, 20L);

        assertThat(result.getRecords()).hasSize(1);
    }
}
