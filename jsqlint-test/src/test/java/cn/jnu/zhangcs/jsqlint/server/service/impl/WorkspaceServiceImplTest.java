package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.entity.Workspace;
import cn.jnu.zhangcs.jsqlint.server.entity.WorkspaceProject;
import cn.jnu.zhangcs.jsqlint.server.mapper.WorkspaceMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.WorkspaceProjectMapper;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkspaceServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        // wrapper.select(字段...) 需要实体列缓存，纯单元测试下需手动初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WorkspaceProject.class);
    }

    @Mock
    private WorkspaceMapper workspaceMapper;

    @Mock
    private WorkspaceProjectMapper workspaceProjectMapper;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private WorkspaceServiceImpl workspaceService;

    @Test
    void delete_workspaceNotFound_throwsException() {
        when(workspaceMapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> workspaceService.delete(9L))
                .isInstanceOf(BizException.class)
                .hasMessage("工作空间不存在");
    }

    @Test
    void delete_hasProjects_throwsException() {
        when(workspaceMapper.selectById(1L)).thenReturn(new Workspace());
        when(workspaceProjectMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> workspaceService.delete(1L))
                .isInstanceOf(BizException.class)
                .hasMessage("工作空间下有项目，不允许删除");
        verify(workspaceMapper, never()).deleteById(1L);
    }

    @Test
    void delete_noProjects_deletedSuccessfully() {
        when(workspaceMapper.selectById(1L)).thenReturn(new Workspace());
        when(workspaceProjectMapper.selectCount(any())).thenReturn(0L);

        workspaceService.delete(1L);

        verify(workspaceMapper).deleteById(1L);
    }

    @Test
    void getById_delegatesToMapper() {
        Workspace workspace = new Workspace();
        when(workspaceMapper.selectById(1L)).thenReturn(workspace);

        assertThat(workspaceService.getById(1L)).isSameAs(workspace);
    }

    @Test
    void list_nullUserId_returnsEmptyList() {
        assertThat(workspaceService.list(null)).isEmpty();
    }

    @Test
    void list_delegatesToMapper() {
        List<Workspace> list = List.of(new Workspace());
        when(workspaceMapper.selectList(any())).thenReturn(list);

        assertThat(workspaceService.list(1L)).isSameAs(list);
    }

    @Test
    void addProject_workspaceNotFound_throwsException() {
        when(workspaceMapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> workspaceService.addProject(9L, 1L))
                .isInstanceOf(BizException.class)
                .hasMessage("工作空间不存在");
    }

    @Test
    void addProject_projectNotFound_throwsException() {
        when(workspaceMapper.selectById(1L)).thenReturn(new Workspace());
        when(projectService.getById(1L)).thenReturn(null);

        assertThatThrownBy(() -> workspaceService.addProject(1L, 1L))
                .isInstanceOf(BizException.class)
                .hasMessage("项目不存在");
    }

    @Test
    void addProject_projectAlreadyInOtherWorkspace_throwsException() {
        when(workspaceMapper.selectById(1L)).thenReturn(new Workspace());
        when(projectService.getById(2L)).thenReturn(new Project());
        when(workspaceProjectMapper.selectOne(any())).thenReturn(new WorkspaceProject());

        assertThatThrownBy(() -> workspaceService.addProject(1L, 2L))
                .isInstanceOf(BizException.class)
                .hasMessage("项目已在其他工作空间中");
    }

    @Test
    void addProject_insertsRelation() {
        when(workspaceMapper.selectById(1L)).thenReturn(new Workspace());
        when(projectService.getById(2L)).thenReturn(new Project());
        when(workspaceProjectMapper.selectOne(any())).thenReturn(null);

        workspaceService.addProject(1L, 2L);

        verify(workspaceProjectMapper).insert(any(WorkspaceProject.class));
    }

    @Test
    void removeProject_relationNotFound_throwsException() {
        when(workspaceProjectMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> workspaceService.removeProject(1L, 2L))
                .isInstanceOf(BizException.class)
                .hasMessage("项目不在该工作空间中");
    }

    @Test
    void removeProject_deletesByRelationId() {
        WorkspaceProject relation = new WorkspaceProject();
        relation.setId(7L);
        when(workspaceProjectMapper.selectOne(any())).thenReturn(relation);

        workspaceService.removeProject(1L, 2L);

        verify(workspaceProjectMapper).deleteById(7L);
    }

    @Test
    void getProjects_noRelations_returnsEmptyList() {
        when(workspaceProjectMapper.selectList(any())).thenReturn(List.of());

        assertThat(workspaceService.getProjects(1L)).isEmpty();
    }

    @Test
    void getProjects_queriesByProjectIds() {
        WorkspaceProject relation = new WorkspaceProject();
        relation.setProjectId(2L);
        when(workspaceProjectMapper.selectList(any())).thenReturn(List.of(relation));
        List<Project> projects = List.of(new Project());
        when(projectService.getByIds(any())).thenReturn(projects);

        assertThat(workspaceService.getProjects(1L)).isSameAs(projects);
    }

    @Test
    void getWorkspaceByProjectId_noRelation_returnsNull() {
        when(workspaceProjectMapper.selectOne(any())).thenReturn(null);

        assertThat(workspaceService.getWorkspaceByProjectId(2L)).isNull();
    }

    @Test
    void getWorkspaceByProjectId_returnsOwningWorkspace() {
        WorkspaceProject relation = new WorkspaceProject();
        relation.setWorkspaceId(3L);
        when(workspaceProjectMapper.selectOne(any())).thenReturn(relation);
        Workspace workspace = new Workspace();
        when(workspaceMapper.selectById(3L)).thenReturn(workspace);

        assertThat(workspaceService.getWorkspaceByProjectId(2L)).isSameAs(workspace);
    }

    @Test
    void create_duplicateName_throwsException() {
        when(workspaceMapper.selectList(any())).thenReturn(List.of(new Workspace()));

        assertThatThrownBy(() -> workspaceService.create(1L, "默认空间"))
                .isInstanceOf(BizException.class)
                .hasMessage("工作空间已存在");
    }

    @Test
    void create_fillsNameAndCreator() {
        when(workspaceMapper.selectList(any())).thenReturn(List.of());

        Workspace result = workspaceService.create(1L, "默认空间");

        assertThat(result.getName()).isEqualTo("默认空间");
        assertThat(result.getCreatorId()).isEqualTo(1L);
        verify(workspaceMapper).insert(result);
    }
}
