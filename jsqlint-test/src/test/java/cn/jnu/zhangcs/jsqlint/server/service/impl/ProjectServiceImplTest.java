package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectMemberRole;
import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStatus;
import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStepType;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectMember;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersion;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.mapper.ProjectMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.ProjectMemberMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.ProjectVersionMapper;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectVersionService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectVersionStepService;
import cn.jnu.zhangcs.jsqlint.server.service.UserGroupService;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ProjectServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        // wrapper.set(...)/select(字段...) 需要实体列缓存，纯单元测试下需手动初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Project.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ProjectMember.class);
    }

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private ProjectVersionService versionService;

    @Mock
    private ProjectMemberMapper projectMemberMapper;

    @Mock
    private UserService userService;

    @Mock
    private UserGroupService userGroupService;

    @Mock
    private ProjectVersionMapper versionMapper;

    @Mock
    private ProjectVersionStepService projectVersionStepService;

    @InjectMocks
    private ProjectServiceImpl projectService;

    @Test
    void create_insertsProjectAndAddsCreatorAsAdminMember() {
        Project project = new Project();
        project.setCreateUserId(10L);
        // mock 的 insert 不会回填主键，需手动模拟
        doAnswer(inv -> {
            inv.getArgument(0, Project.class).setId(100L);
            return 1;
        }).when(projectMapper).insert(any(Project.class));
        when(projectMapper.selectById(100L)).thenReturn(project);
        User creator = new User();
        creator.setId(10L);
        when(userService.getById(10L)).thenReturn(creator);
        when(projectMemberMapper.selectOne(any())).thenReturn(null);

        Project result = projectService.create(project);

        assertThat(result).isSameAs(project);
        ArgumentCaptor<ProjectMember> captor = ArgumentCaptor.forClass(ProjectMember.class);
        verify(projectMemberMapper).insert(captor.capture());
        assertThat(captor.getValue().getProjectId()).isEqualTo(100L);
        assertThat(captor.getValue().getUserId()).isEqualTo(10L);
        assertThat(captor.getValue().getRole()).isEqualTo(ProjectMemberRole.ADMIN.getCode());
    }

    @Test
    void create_noCreator_skipsMemberInsert() {
        Project project = new Project();

        projectService.create(project);

        verify(projectMapper).insert(project);
        verify(projectMemberMapper, never()).insert(any(ProjectMember.class));
    }

    @Test
    void updateById_projectNotFound_throwsException() {
        when(projectMapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> projectService.updateById(9L, new Project()))
                .isInstanceOf(BizException.class)
                .hasMessage("项目不存在");
    }

    @Test
    void updateById_executesUpdateAndReturnsLatest() {
        Project project = new Project();
        project.setRef("master");
        project.setStatus(1);
        Project latest = new Project();
        when(projectMapper.selectById(1L)).thenReturn(new Project(), latest);

        Project result = projectService.updateById(1L, project);

        verify(projectMapper).update(any(), any());
        assertThat(result).isSameAs(latest);
    }

    @Test
    void delete_projectNotFound_throwsException() {
        when(projectMapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> projectService.delete(9L))
                .isInstanceOf(BizException.class)
                .hasMessage("项目不存在");
    }

    @Test
    void delete_cascadesMembersStepsVersionsAndProject() {
        when(projectMapper.selectById(1L)).thenReturn(new Project());

        projectService.delete(1L);

        verify(projectMemberMapper).delete(any());
        verify(projectVersionStepService).deleteByProjectId(1L);
        verify(versionService).delete(1L);
        verify(projectMapper).deleteById(1L);
    }

    @Test
    void getById_null_returnsNull() {
        assertThat(projectService.getById(null)).isNull();
    }

    @Test
    void getById_delegatesToMapper() {
        Project project = new Project();
        when(projectMapper.selectById(1L)).thenReturn(project);

        assertThat(projectService.getById(1L)).isSameAs(project);
    }

    @Test
    void getByKeyword_delegatesToMapper() {
        List<Project> list = List.of(new Project());
        when(projectMapper.selectList(any())).thenReturn(list);

        assertThat(projectService.getByKeyword("订单", true)).isSameAs(list);
    }

    @Test
    void getByIds_emptyCollection_returnsEmptyList() {
        assertThat(projectService.getByIds(List.of())).isEmpty();
        assertThat(projectService.getByIds(null)).isEmpty();
    }

    @Test
    void getByUser_queriesMembershipThenProjects() {
        ProjectMember member = new ProjectMember();
        member.setProjectId(3L);
        when(projectMemberMapper.selectList(any())).thenReturn(List.of(member));
        List<Project> projects = List.of(new Project());
        when(projectMapper.selectByIds(any())).thenReturn(projects);

        assertThat(projectService.getByUser(10L)).isSameAs(projects);
    }

    @Test
    void getByUser_noMembership_returnsEmptyList() {
        when(projectMemberMapper.selectList(any())).thenReturn(List.of());

        assertThat(projectService.getByUser(10L)).isEmpty();
    }

    @Test
    void getLastVersion_nullProjectId_returnsNull() {
        assertThat(projectService.getLastVersion(null)).isNull();
    }

    @Test
    void getLastVersion_delegatesToVersionService() {
        ProjectVersion version = new ProjectVersion();
        when(versionService.getLast(1L)).thenReturn(version);

        assertThat(projectService.getLastVersion(1L)).isSameAs(version);
    }

    @Test
    void addMember_projectNotFound_throwsException() {
        when(projectMapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> projectService.addMember(9L, 10L, false))
                .isInstanceOf(BizException.class)
                .hasMessage("项目不存在");
    }

    @Test
    void addMember_userNotFound_throwsException() {
        when(projectMapper.selectById(1L)).thenReturn(new Project());
        when(userService.getById(10L)).thenReturn(null);

        assertThatThrownBy(() -> projectService.addMember(1L, 10L, false))
                .isInstanceOf(BizException.class)
                .hasMessage("用户不存在");
    }

    @Test
    void addMember_alreadyMember_throwsException() {
        when(projectMapper.selectById(1L)).thenReturn(new Project());
        when(userService.getById(10L)).thenReturn(new User());
        when(projectMemberMapper.selectOne(any())).thenReturn(new ProjectMember());

        assertThatThrownBy(() -> projectService.addMember(1L, 10L, false))
                .isInstanceOf(BizException.class)
                .hasMessage("用户已是项目成员");
    }

    @Test
    void addMember_normalMember_assignsNormalRole() {
        when(projectMapper.selectById(1L)).thenReturn(new Project());
        when(userService.getById(10L)).thenReturn(new User());
        when(projectMemberMapper.selectOne(any())).thenReturn(null);

        projectService.addMember(1L, 10L, false);

        ArgumentCaptor<ProjectMember> captor = ArgumentCaptor.forClass(ProjectMember.class);
        verify(projectMemberMapper).insert(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(ProjectMemberRole.NORMAL.getCode());
    }

    @Test
    void addMembers_emptyUserList_returnsImmediately() {
        projectService.addMembers(1L, List.of(), true);

        verify(projectMapper, never()).selectById(any());
    }

    @Test
    void addMembers_skipsMissingUsersAndExistingMembers() {
        when(projectMapper.selectById(1L)).thenReturn(new Project());
        User user = new User();
        user.setId(10L);
        when(userService.getById(10L)).thenReturn(user);
        when(userService.getById(11L)).thenReturn(user);
        // 第 1 次查询无关系（插入），第 2 次已有关系（跳过）
        when(projectMemberMapper.selectOne(any())).thenReturn(null, new ProjectMember());

        projectService.addMembers(1L, List.of(10L, 11L), false);

        verify(projectMemberMapper, org.mockito.Mockito.times(1)).insert(any(ProjectMember.class));
    }

    @Test
    void addMembersByGroup_projectNotFound_throwsException() {
        when(projectMapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> projectService.addMembersByGroup(9L, 5L, false))
                .isInstanceOf(BizException.class)
                .hasMessage("项目不存在");
    }

    @Test
    void addMembersByGroup_emptyGroup_returnsImmediately() {
        when(projectMapper.selectById(1L)).thenReturn(new Project());
        when(userGroupService.getMembers(5L)).thenReturn(List.of());

        projectService.addMembersByGroup(1L, 5L, false);

        verify(projectMemberMapper, never()).insert(any(ProjectMember.class));
    }

    @Test
    void addMembersByGroup_delegatesToBatchAddWithGroupUserIds() {
        when(projectMapper.selectById(1L)).thenReturn(new Project());
        User user1 = new User();
        user1.setId(10L);
        User user2 = new User();
        user2.setId(11L);
        when(userGroupService.getMembers(5L)).thenReturn(List.of(user1, user2));
        // addMembers 内部：项目存在、用户存在、无已有关系
        when(projectMapper.selectById(1L)).thenReturn(new Project());
        when(userService.getById(10L)).thenReturn(user1);
        when(userService.getById(11L)).thenReturn(user2);
        when(projectMemberMapper.selectOne(any())).thenReturn(null);

        projectService.addMembersByGroup(1L, 5L, true);

        verify(projectMemberMapper, org.mockito.Mockito.times(2)).insert(any(ProjectMember.class));
    }

    @Test
    void removeMember_notMember_throwsException() {
        when(projectMemberMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> projectService.removeMember(1L, 10L))
                .isInstanceOf(BizException.class)
                .hasMessage("用户不是项目成员");
    }

    @Test
    void removeMember_deletesByRelationId() {
        ProjectMember member = new ProjectMember();
        member.setId(7L);
        when(projectMemberMapper.selectOne(any())).thenReturn(member);

        projectService.removeMember(1L, 10L);

        verify(projectMemberMapper).deleteById(7L);
    }

    @Test
    void updateMemberRole_notMember_throwsException() {
        when(projectMemberMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> projectService.updateMemberRole(1L, 10L, true))
                .isInstanceOf(BizException.class)
                .hasMessage("用户不是项目成员");
    }

    @Test
    void updateMemberRole_updatesRoleAndSaves() {
        ProjectMember member = new ProjectMember();
        when(projectMemberMapper.selectOne(any())).thenReturn(member);

        projectService.updateMemberRole(1L, 10L, true);

        assertThat(member.getRole()).isEqualTo(ProjectMemberRole.ADMIN.getCode());
        verify(projectMemberMapper).updateById(member);
    }

    @Test
    void getMembers_noMembers_returnsEmptyList() {
        when(projectMemberMapper.selectList(any())).thenReturn(List.of());

        assertThat(projectService.getMembers(1L)).isEmpty();
    }

    @Test
    void getMembers_fillsUserAccountAndName() {
        ProjectMember member = new ProjectMember();
        member.setUserId(10L);
        when(projectMemberMapper.selectList(any())).thenReturn(List.of(member));
        User user = new User();
        user.setId(10L);
        user.setAccount("zhangsan");
        user.setUsername("张三");
        when(userService.getByIds(any())).thenReturn(List.of(user));

        List<ProjectMember> members = projectService.getMembers(1L);

        assertThat(members.get(0).getAccount()).isEqualTo("zhangsan");
        assertThat(members.get(0).getUsername()).isEqualTo("张三");
    }

    @Test
    void getMembers_orphanMember_skipsUserFillWithoutError() {
        // 成员对应的用户已被删除（历史遗留孤儿数据），列表不应 NPE
        ProjectMember orphan = new ProjectMember();
        orphan.setUserId(99L);
        ProjectMember normal = new ProjectMember();
        normal.setUserId(10L);
        when(projectMemberMapper.selectList(any())).thenReturn(List.of(orphan, normal));
        User user = new User();
        user.setId(10L);
        user.setAccount("zhangsan");
        user.setUsername("张三");
        when(userService.getByIds(any())).thenReturn(List.of(user));

        List<ProjectMember> members = projectService.getMembers(1L);

        assertThat(members).hasSize(2);
        // 孤儿成员的用户信息保持为空，正常成员照常填充
        assertThat(members.get(0).getAccount()).isNull();
        assertThat(members.get(1).getAccount()).isEqualTo("zhangsan");
    }

    @Test
    void isAdmin_checksMemberRole() {
        ProjectMember admin = new ProjectMember();
        admin.setRole(ProjectMemberRole.ADMIN.getCode());
        when(projectMemberMapper.selectOne(any())).thenReturn(admin);
        assertThat(projectService.isAdmin(1L, 10L)).isTrue();

        when(projectMemberMapper.selectOne(any())).thenReturn(null);
        assertThat(projectService.isAdmin(1L, 10L)).isFalse();
    }

    @Test
    void isSyncing_runningAtSyncCodeStep_returnsTrue() {
        ProjectVersion version = new ProjectVersion();
        version.setOverallStatus(ProjectVersionStatus.RUNNING.getCode());
        version.setCurrentStep(ProjectVersionStepType.SYNC_CODE.getCode());
        when(versionService.getLast(1L)).thenReturn(version);

        assertThat(projectService.isSyncing(1L)).isTrue();
    }

    @Test
    void isSyncing_noVersionOrNotRunning_returnsFalse() {
        when(versionService.getLast(1L)).thenReturn(null);
        assertThat(projectService.isSyncing(1L)).isFalse();

        ProjectVersion version = new ProjectVersion();
        version.setOverallStatus(ProjectVersionStatus.SUCCESS.getCode());
        when(versionService.getLast(1L)).thenReturn(version);
        assertThat(projectService.isSyncing(1L)).isFalse();
    }

    @Test
    void addVersion_fillsProjectIdAndOperationTime() {
        ProjectVersion version = new ProjectVersion();

        projectService.addVersion(1L, version);

        assertThat(version.getProjectId()).isEqualTo(1L);
        assertThat(version.getOperationTime()).isNotNull();
        verify(versionMapper).insert(version);
    }

    @Test
    void addVersion_existingOperationTime_notOverwritten() {
        ProjectVersion version = new ProjectVersion();
        java.time.LocalDateTime time = java.time.LocalDateTime.of(2026, 1, 1, 0, 0);
        version.setOperationTime(time);

        projectService.addVersion(1L, version);

        assertThat(version.getOperationTime()).isEqualTo(time);
    }

    @Test
    void isAlreadySync_evaluatesStepByStep() {
        // 项目不存在
        when(projectMapper.selectById(9L)).thenReturn(null);
        assertThat(projectService.isAlreadySync(9L)).isFalse();

        // 版本不存在
        Project project = new Project();
        when(projectMapper.selectById(1L)).thenReturn(project);
        when(versionService.getLast(1L)).thenReturn(null);
        assertThat(projectService.isAlreadySync(1L)).isFalse();

        // 同步步骤未成功
        ProjectVersion version = new ProjectVersion();
        version.setId(2L);
        when(versionService.getLast(1L)).thenReturn(version);
        when(projectVersionStepService.isStepSuccess(2L, ProjectVersionStepType.SYNC_CODE)).thenReturn(false);
        assertThat(projectService.isAlreadySync(1L)).isFalse();

        // 同步步骤成功
        when(projectVersionStepService.isStepSuccess(2L, ProjectVersionStepType.SYNC_CODE)).thenReturn(true);
        assertThat(projectService.isAlreadySync(1L)).isTrue();
    }

    @Test
    void isAlreadyCompiled_checksCompileStep() {
        ProjectVersion version = new ProjectVersion();
        version.setId(2L);
        when(versionService.getLast(1L)).thenReturn(version);
        when(projectVersionStepService.isStepSuccess(2L, ProjectVersionStepType.COMPILE_CHECK)).thenReturn(true);
        assertThat(projectService.isAlreadyCompiled(1L)).isTrue();

        when(versionService.getLast(1L)).thenReturn(null);
        assertThat(projectService.isAlreadyCompiled(1L)).isFalse();
    }

    @Test
    void isAlreadyAnalysis_checksSqlScanStep() {
        ProjectVersion version = new ProjectVersion();
        version.setId(2L);
        when(versionService.getLast(1L)).thenReturn(version);
        when(projectVersionStepService.isStepSuccess(2L, ProjectVersionStepType.SQL_SCAN)).thenReturn(true);

        assertThat(projectService.isAlreadyAnalysis(1L)).isTrue();
    }

    @Test
    void isAlreadyAudit_checksRuleAuditStep() {
        ProjectVersion version = new ProjectVersion();
        version.setId(2L);
        when(versionService.getLast(1L)).thenReturn(version);
        when(projectVersionStepService.isStepSuccess(2L, ProjectVersionStepType.RULE_AUDIT)).thenReturn(true);

        assertThat(projectService.isAlreadyAudit(1L)).isTrue();
    }

    @Test
    void updateVersion_overridesVersionIdAndSaves() {
        ProjectVersion version = new ProjectVersion();

        projectService.updateVersion(5L, version);

        assertThat(version.getId()).isEqualTo(5L);
        verify(versionMapper).updateById(version);
    }
}
