package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.InspectionConfig;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.mapper.InspectionConfigMapper;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link InspectionConfigServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class InspectionConfigServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        // wrapper.set(...)/orderBy(...) 需要实体列缓存，纯单元测试下需手动初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), InspectionConfig.class);
    }

    @Mock
    private InspectionConfigMapper mapper;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private InspectionConfigServiceImpl inspectionConfigService;

    private void mockAdmin(long projectId) {
        when(projectService.getById(projectId)).thenReturn(new Project());
        when(projectService.isAdmin(projectId, 10L)).thenReturn(true);
    }

    @Test
    void saveOrUpdate_projectNotFound_throwsException() {
        when(projectService.getById(9L)).thenReturn(null);

        assertThatThrownBy(() -> inspectionConfigService.saveOrUpdate(9L, "0 0 2 * * ?", true, 10L))
                .isInstanceOf(BizException.class)
                .hasMessage("项目不存在");
    }

    @Test
    void saveOrUpdate_notProjectAdmin_throwsException() {
        when(projectService.getById(1L)).thenReturn(new Project());
        when(projectService.isAdmin(1L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> inspectionConfigService.saveOrUpdate(1L, "0 0 2 * * ?", true, 10L))
                .isInstanceOf(BizException.class)
                .hasMessage("该操作需要项目管理员权限");
    }

    @Test
    void saveOrUpdate_invalidCronExpr_throwsException() {
        mockAdmin(1L);

        assertThatThrownBy(() -> inspectionConfigService.saveOrUpdate(1L, "not-a-cron", true, 10L))
                .isInstanceOf(BizException.class)
                .hasMessageStartingWith("cron表达式不合法");
    }

    @Test
    void saveOrUpdate_newConfig_fillsFieldsAndInserts() {
        mockAdmin(1L);
        when(mapper.selectOne(any())).thenReturn(null);

        InspectionConfig result = inspectionConfigService.saveOrUpdate(1L, "0 0 2 * * ?", true, 10L);

        ArgumentCaptor<InspectionConfig> captor = ArgumentCaptor.forClass(InspectionConfig.class);
        verify(mapper).insert(captor.capture());
        InspectionConfig config = captor.getValue();
        assertThat(config.getProjectId()).isEqualTo(1L);
        assertThat(config.getCronExpr()).isEqualTo("0 0 2 * * ?");
        assertThat(config.getEnabled()).isEqualTo(1);
        assertThat(config.getNextExecTime()).isNotNull();
        assertThat(config.getLastStatus()).isNull();
        assertThat(config.getOperatorId()).isEqualTo(10L);
        assertThat(result).isSameAs(config);
    }

    @Test
    void saveOrUpdate_nullEnabled_treatedAsEnabled() {
        mockAdmin(1L);
        when(mapper.selectOne(any())).thenReturn(null);

        inspectionConfigService.saveOrUpdate(1L, "0 0 2 * * ?", null, 10L);

        ArgumentCaptor<InspectionConfig> captor = ArgumentCaptor.forClass(InspectionConfig.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getEnabled()).isEqualTo(1);
    }

    @Test
    void saveOrUpdate_disabled_clearsNextExecTime() {
        mockAdmin(1L);
        when(mapper.selectOne(any())).thenReturn(null);

        inspectionConfigService.saveOrUpdate(1L, "0 0 2 * * ?", false, 10L);

        ArgumentCaptor<InspectionConfig> captor = ArgumentCaptor.forClass(InspectionConfig.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getEnabled()).isZero();
        assertThat(captor.getValue().getNextExecTime()).isNull();
        assertThat(captor.getValue().getLastStatus()).isEqualTo("DISABLED");
    }

    @Test
    void saveOrUpdate_existingConfig_updates() {
        mockAdmin(1L);
        InspectionConfig existing = new InspectionConfig();
        existing.setId(5L);
        when(mapper.selectOne(any())).thenReturn(existing);

        InspectionConfig result = inspectionConfigService.saveOrUpdate(1L, "0 0 3 * * ?", true, 10L);

        verify(mapper, never()).insert(any(InspectionConfig.class));
        verify(mapper).updateById(existing);
        assertThat(existing.getCronExpr()).isEqualTo("0 0 3 * * ?");
        assertThat(existing.getEnabled()).isEqualTo(1);
        assertThat(existing.getNextExecTime()).isNotNull();
        assertThat(result).isSameAs(existing);
    }

    @Test
    void saveOrUpdate_updateKeepsNonDisabledStatus() {
        mockAdmin(1L);
        InspectionConfig existing = new InspectionConfig();
        existing.setId(5L);
        existing.setLastStatus("SUCCESS");
        when(mapper.selectOne(any())).thenReturn(existing);

        inspectionConfigService.saveOrUpdate(1L, "0 0 3 * * ?", true, 10L);

        // 非 DISABLED 状态在启用时保持不变
        assertThat(existing.getLastStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void saveOrUpdate_updateDisabled_setsStatusToDisabled() {
        mockAdmin(1L);
        InspectionConfig existing = new InspectionConfig();
        existing.setId(5L);
        existing.setLastStatus("SUCCESS");
        when(mapper.selectOne(any())).thenReturn(existing);

        inspectionConfigService.saveOrUpdate(1L, "0 0 3 * * ?", false, 10L);

        assertThat(existing.getEnabled()).isZero();
        assertThat(existing.getLastStatus()).isEqualTo("DISABLED");
        assertThat(existing.getNextExecTime()).isNull();
    }

    @Test
    void getByProjectId_delegatesToMapper() {
        InspectionConfig config = new InspectionConfig();
        when(mapper.selectOne(any())).thenReturn(config);

        assertThat(inspectionConfigService.getByProjectId(1L)).isSameAs(config);
    }

    @Test
    void list_fillsProjectName() {
        InspectionConfig config = new InspectionConfig();
        config.setProjectId(1L);
        when(mapper.selectList(any())).thenReturn(List.of(config));
        Project project = new Project();
        project.setName("订单系统");
        when(projectService.getById(1L)).thenReturn(project);

        List<InspectionConfig> result = inspectionConfigService.list(true);

        assertThat(result.get(0).getProjectName()).isEqualTo("订单系统");
    }

    @Test
    void list_projectNotFound_keepsNameNull() {
        InspectionConfig config = new InspectionConfig();
        config.setProjectId(1L);
        when(mapper.selectList(any())).thenReturn(List.of(config));
        when(projectService.getById(1L)).thenReturn(null);

        List<InspectionConfig> result = inspectionConfigService.list(null);

        assertThat(result.get(0).getProjectName()).isNull();
    }

    @Test
    void setEnabled_configNotFound_throwsException() {
        mockAdmin(1L);
        when(mapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> inspectionConfigService.setEnabled(1L, true, 10L))
                .isInstanceOf(BizException.class)
                .hasMessage("巡检配置不存在");
    }

    @Test
    void setEnabled_enabled_computesNextExecTime() {
        mockAdmin(1L);
        InspectionConfig config = new InspectionConfig();
        config.setId(5L);
        config.setCronExpr("0 0 2 * * ?");
        config.setLastStatus("DISABLED");
        when(mapper.selectOne(any())).thenReturn(config);

        inspectionConfigService.setEnabled(1L, true, 10L);

        verify(mapper).updateById(config);
        assertThat(config.getEnabled()).isEqualTo(1);
        assertThat(config.getNextExecTime()).isNotNull();
        // DISABLED 状态启用后清空
        assertThat(config.getLastStatus()).isNull();
    }

    @Test
    void setEnabled_disabled_setsStatusToDisabled() {
        mockAdmin(1L);
        InspectionConfig config = new InspectionConfig();
        config.setId(5L);
        config.setCronExpr("0 0 2 * * ?");
        when(mapper.selectOne(any())).thenReturn(config);

        inspectionConfigService.setEnabled(1L, false, 10L);

        assertThat(config.getEnabled()).isZero();
        assertThat(config.getNextExecTime()).isNull();
        assertThat(config.getLastStatus()).isEqualTo("DISABLED");
    }

    @Test
    void deleteByProjectId_checksAdminThenDeletes() {
        mockAdmin(1L);

        inspectionConfigService.deleteByProjectId(1L, 10L);

        verify(mapper).delete(any());
    }

    @Test
    void findDueConfigs_returnsPagedRecords() {
        Page<InspectionConfig> page = new Page<>(0, 10);
        page.setRecords(List.of(new InspectionConfig()));
        when(mapper.selectPage(any(), any())).thenReturn(page);

        assertThat(inspectionConfigService.findDueConfigs(10)).hasSize(1);
    }

    @Test
    void tryClaimDue_judgesByUpdatedRows() {
        when(mapper.update(any(), any())).thenReturn(1);
        assertThat(inspectionConfigService.tryClaimDue(5L)).isTrue();

        when(mapper.update(any(), any())).thenReturn(0);
        assertThat(inspectionConfigService.tryClaimDue(5L)).isFalse();
    }

    @Test
    void scheduleNext_executesSingleParamUpdate() {
        inspectionConfigService.scheduleNext(5L, "0 0 2 * * ?");

        verify(mapper).update(any());
    }

    @Test
    void markResult_configExists_updates() {
        InspectionConfig config = new InspectionConfig();
        config.setId(5L);
        when(mapper.selectOne(any())).thenReturn(config);

        inspectionConfigService.markResult(1L, "SUCCESS", "巡检完成");

        verify(mapper).update(any());
    }

    @Test
    void markResult_configNotFound_doesNotUpdate() {
        when(mapper.selectOne(any())).thenReturn(null);

        inspectionConfigService.markResult(1L, "SUCCESS", "巡检完成");

        verify(mapper, never()).update(any());
    }

    @Test
    void computeNextExecTime_validCron_returnsTimestamp() {
        Long next = inspectionConfigService.computeNextExecTime("0 0 2 * * ?");

        assertThat(next).isNotNull();
        assertThat(next).isGreaterThan(System.currentTimeMillis());
    }

    @Test
    void computeNextExecTime_invalidCron_throwsException() {
        assertThatThrownBy(() -> inspectionConfigService.computeNextExecTime("bad-cron"))
                .isInstanceOf(BizException.class)
                .hasMessageStartingWith("cron表达式不合法");
    }
}
