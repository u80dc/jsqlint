package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.MavenRepository;
import cn.jnu.zhangcs.jsqlint.server.mapper.MavenRepositoryMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MavenRepositoryServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class MavenRepositoryServiceImplTest {

    @Mock
    private MavenRepositoryMapper repositoryMapper;

    @InjectMocks
    private MavenRepositoryServiceImpl repositoryService;

    @Test
    void create_duplicateRepoId_throwsException() {
        MavenRepository repository = new MavenRepository();
        repository.setRepoId("central");
        when(repositoryMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> repositoryService.create(repository))
                .isInstanceOf(BizException.class)
                .hasMessage("仓库ID已存在: central");
    }

    @Test
    void create_fillsDefaultConfigAndInserts() {
        MavenRepository repository = new MavenRepository();
        repository.setRepoId("central");
        when(repositoryMapper.selectCount(any())).thenReturn(0L);

        MavenRepository result = repositoryService.create(repository);

        assertThat(result).isSameAs(repository);
        assertThat(repository.getLayout()).isEqualTo("default");
        // 实体字段默认 false，create 仅在 null 时补默认值，此处保持 false 不变为 true
        assertThat(repository.getReleasesEnabled()).isFalse();
        assertThat(repository.getReleasesUpdatePolicy()).isEqualTo("daily");
        assertThat(repository.getReleasesChecksumPolicy()).isEqualTo("fail");
        assertThat(repository.getSnapshotsEnabled()).isFalse();
        assertThat(repository.getSnapshotsUpdatePolicy()).isEqualTo("daily");
        assertThat(repository.getSnapshotsChecksumPolicy()).isEqualTo("fail");
        verify(repositoryMapper).insert(repository);
    }

    @Test
    void create_releasesEnabledExplicitlyNull_fillsTrue() {
        MavenRepository repository = new MavenRepository();
        repository.setRepoId("central");
        repository.setReleasesEnabled(null);
        when(repositoryMapper.selectCount(any())).thenReturn(0L);

        repositoryService.create(repository);

        assertThat(repository.getReleasesEnabled()).isTrue();
    }

    @Test
    void create_existingConfigNotOverwrittenByDefaults() {
        MavenRepository repository = new MavenRepository();
        repository.setRepoId("central");
        repository.setLayout("flat");
        repository.setReleasesEnabled(false);
        repository.setSnapshotsEnabled(true);
        when(repositoryMapper.selectCount(any())).thenReturn(0L);

        repositoryService.create(repository);

        assertThat(repository.getLayout()).isEqualTo("flat");
        assertThat(repository.getReleasesEnabled()).isFalse();
        assertThat(repository.getSnapshotsEnabled()).isTrue();
    }

    @Test
    void update_notFound_throwsException() {
        MavenRepository repository = new MavenRepository();
        repository.setId(1L);
        when(repositoryMapper.selectById(1L)).thenReturn(null);

        assertThatThrownBy(() -> repositoryService.update(repository))
                .isInstanceOf(BizException.class)
                .hasMessage("仓库不存在");
    }

    @Test
    void update_repoIdConflictsWithOtherRepository_throwsException() {
        MavenRepository repository = new MavenRepository();
        repository.setId(1L);
        repository.setRepoId("central");
        when(repositoryMapper.selectById(1L)).thenReturn(new MavenRepository());
        when(repositoryMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> repositoryService.update(repository))
                .isInstanceOf(BizException.class)
                .hasMessage("仓库ID已存在: central");
    }

    @Test
    void update_returnsLatestDataAfterUpdate() {
        MavenRepository repository = new MavenRepository();
        repository.setId(1L);
        repository.setRepoId("central");
        MavenRepository latest = new MavenRepository();
        latest.setRepoId("central");
        when(repositoryMapper.selectById(1L)).thenReturn(new MavenRepository(), latest);
        when(repositoryMapper.selectCount(any())).thenReturn(0L);

        MavenRepository result = repositoryService.update(repository);

        verify(repositoryMapper).updateById(repository);
        assertThat(result).isSameAs(latest);
    }

    @Test
    void delete_delegatesToMapper() {
        repositoryService.delete(5L);
        verify(repositoryMapper).deleteById(5L);
    }

    @Test
    void getById_delegatesToMapper() {
        MavenRepository repository = new MavenRepository();
        when(repositoryMapper.selectById(5L)).thenReturn(repository);

        assertThat(repositoryService.getById(5L)).isSameAs(repository);
    }

    @Test
    void list_delegatesToMapper() {
        List<MavenRepository> list = List.of(new MavenRepository());
        when(repositoryMapper.selectList(any())).thenReturn(list);

        assertThat(repositoryService.list(1)).isSameAs(list);
    }

    @Test
    void getByType_nullType_returnsEmptyList() {
        assertThat(repositoryService.getByType(null, 1)).isEmpty();
    }

    @Test
    void getByType_delegatesToMapper() {
        List<MavenRepository> list = List.of(new MavenRepository());
        when(repositoryMapper.selectList(any())).thenReturn(list);

        assertThat(repositoryService.getByType(2, 1)).isSameAs(list);
    }
}
