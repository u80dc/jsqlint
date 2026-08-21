package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectVersionStatus;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersion;
import cn.jnu.zhangcs.jsqlint.server.mapper.ProjectVersionMapper;
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
 * {@link ProjectVersionServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class ProjectVersionServiceImplTest {

    @Mock
    private ProjectVersionMapper mapper;

    @InjectMocks
    private ProjectVersionServiceImpl versionService;

    @Test
    void delete_nullProjectId_returnsDirectly() {
        versionService.delete(null);
        verify(mapper, never()).delete(any());
    }

    @Test
    void delete_deletesByProjectId() {
        versionService.delete(5L);
        verify(mapper).delete(any());
    }

    @Test
    void getList_nullProjectId_returnsEmptyList() {
        assertThat(versionService.getList(null)).isEmpty();
    }

    @Test
    void getList_delegatesToMapper() {
        List<ProjectVersion> list = List.of(new ProjectVersion());
        when(mapper.selectList(any())).thenReturn(list);

        assertThat(versionService.getList(5L, ProjectVersionStatus.SUCCESS)).isSameAs(list);
    }

    @Test
    void add_delegatesToMapper() {
        ProjectVersion version = new ProjectVersion();
        versionService.add(version);
        verify(mapper).insert(version);
    }

    @Test
    void getLast_nullProjectId_returnsNull() {
        assertThat(versionService.getLast(null)).isNull();
    }

    @Test
    void getLast_delegatesToMapper() {
        ProjectVersion version = new ProjectVersion();
        when(mapper.selectOne(any())).thenReturn(version);

        assertThat(versionService.getLast(5L)).isSameAs(version);
    }

    @Test
    void getById_nullId_returnsNull() {
        assertThat(versionService.getById(null)).isNull();
    }

    @Test
    void getById_delegatesToMapper() {
        ProjectVersion version = new ProjectVersion();
        when(mapper.selectById(7L)).thenReturn(version);

        assertThat(versionService.getById(7L)).isSameAs(version);
    }

    @Test
    void update_versionNotFound_throwsException() {
        when(mapper.selectById(7L)).thenReturn(null);

        assertThatThrownBy(() -> versionService.update(7L, ProjectVersionStatus.SUCCESS, null))
                .isInstanceOf(BizException.class)
                .hasMessage("版本不存在");
    }

    @Test
    void update_overwritesStatusOperationTimeAndMessage() {
        ProjectVersion version = new ProjectVersion();
        version.setMessage("旧消息");
        when(mapper.selectById(7L)).thenReturn(version);

        versionService.update(7L, ProjectVersionStatus.FAILED, "编译失败");

        assertThat(version.getOverallStatus()).isEqualTo(ProjectVersionStatus.FAILED.getCode());
        assertThat(version.getOperationTime()).isNotNull();
        assertThat(version.getMessage()).isEqualTo("编译失败");
        verify(mapper).updateById(version);
    }

    @Test
    void update_blankMessage_keepsOriginalMessage() {
        ProjectVersion version = new ProjectVersion();
        version.setMessage("保留消息");
        when(mapper.selectById(7L)).thenReturn(version);

        versionService.update(7L, ProjectVersionStatus.SUCCESS, " ");

        assertThat(version.getMessage()).isEqualTo("保留消息");
    }

    @Test
    void updateById_nullEntity_returnsDirectly() {
        versionService.updateById(7L, null);
        verify(mapper, never()).updateById(any(ProjectVersion.class));
    }

    @Test
    void updateById_primaryKeyForcedToTargetId() {
        ProjectVersion version = new ProjectVersion();
        version.setId(1L);

        versionService.updateById(7L, version);

        assertThat(version.getId()).isEqualTo(7L);
        verify(mapper).updateById(version);
    }
}
