package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditRule;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditRuleVersion;
import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditRuleMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditRuleVersionMapper;
import cn.jnu.zhangcs.jsqlint.server.model.dto.ProjectAuditRuleDTO;
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
 * {@link ProjectAuditRuleServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class ProjectAuditRuleServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        // wrapper.eq(...) 需要实体列缓存，纯单元测试下需手动初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AuditRule.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AuditRuleVersion.class);
    }

    // SqlAuditTool 会自行前置 package/import，规则内容本身不能带
    private static final String VALID_DRL = """
            rule "project delete check"
                when
                    $f : SqlAuditFact(sqlType == "DELETE", sql not contains " where ")
                then
                    $f.setRiskLevel(RiskLevel.HIGH);
                    $f.addRiskFactor("DELETE_WITHOUT_WHERE");
            end
            """;

    @Mock
    private AuditRuleMapper auditRuleMapper;

    @Mock
    private AuditRuleVersionMapper auditRuleVersionMapper;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private ProjectAuditRuleServiceImpl projectAuditRuleService;

    private ProjectAuditRuleDTO validDto() {
        ProjectAuditRuleDTO dto = new ProjectAuditRuleDTO();
        dto.setName("delete 检查");
        dto.setCode("PROJ_DELETE_CHECK");
        dto.setSeverity("high");
        dto.setCategory("SQL_QUALITY");
        dto.setDroolsContent(VALID_DRL);
        dto.setSuggestion("补充 WHERE 条件");
        return dto;
    }

    private AuditRule existingRule() {
        AuditRule rule = new AuditRule();
        rule.setId(1L);
        rule.setProjectId(1L);
        rule.setName("delete 检查");
        rule.setCode("PROJ_DELETE_CHECK");
        rule.setSeverity("high");
        rule.setCategory("SQL_QUALITY");
        rule.setDroolsContent(VALID_DRL);
        rule.setSuggestion("补充 WHERE 条件");
        rule.setEnabled(1);
        return rule;
    }

    private void mockProjectExists() {
        when(projectService.getById(1L)).thenReturn(new Project());
    }

    @Test
    void listRules_projectNotFound_throwsException() {
        when(projectService.getById(9L)).thenReturn(null);

        assertThatThrownBy(() -> projectAuditRuleService.listRules(9L, 1, 10, null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessage("项目不存在");
    }

    @Test
    void listRules_fillsSeverityAndCategoryDescriptions() {
        mockProjectExists();
        Page<AuditRule> page = new Page<>(1, 10);
        page.setRecords(List.of(existingRule()));
        when(auditRuleMapper.selectPage(any(), any())).thenReturn(page);

        List<AuditRule> result = projectAuditRuleService.listRules(1L, 1, 10, null, null, null);

        assertThat(result.get(0).getSeverityDescription()).isEqualTo("高危");
        assertThat(result.get(0).getCategoryDescription()).isEqualTo("SQL质量");
    }

    @Test
    void countRules_projectNotFound_throwsException() {
        when(projectService.getById(9L)).thenReturn(null);

        assertThatThrownBy(() -> projectAuditRuleService.countRules(9L, null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessage("项目不存在");
    }

    @Test
    void countRules_delegatesToMapper() {
        mockProjectExists();
        when(auditRuleMapper.selectCount(any())).thenReturn(3L);

        assertThat(projectAuditRuleService.countRules(1L, null, null, null)).isEqualTo(3L);
    }

    @Test
    void getRule_fillsDescription() {
        mockProjectExists();
        when(auditRuleMapper.selectOne(any())).thenReturn(existingRule());

        AuditRule result = projectAuditRuleService.getRule(1L, 1L);

        assertThat(result.getSeverityDescription()).isEqualTo("高危");
    }

    @Test
    void getRule_notInProject_throwsException() {
        mockProjectExists();
        when(auditRuleMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> projectAuditRuleService.getRule(1L, 9L))
                .isInstanceOf(BizException.class)
                .hasMessage("项目审核规则不存在");
    }

    @Test
    void createRule_nullDto_throwsException() {
        mockProjectExists();

        assertThatThrownBy(() -> projectAuditRuleService.createRule(1L, null, 10L))
                .isInstanceOf(BizException.class)
                .hasMessage("规则信息不能为空");
    }

    @Test
    void createRule_missingRequiredFields_throwsException() {
        mockProjectExists();
        ProjectAuditRuleDTO dto = validDto();
        dto.setName(" ");

        assertThatThrownBy(() -> projectAuditRuleService.createRule(1L, dto, 10L))
                .isInstanceOf(BizException.class)
                .hasMessage("规则名称不能为空");

        ProjectAuditRuleDTO noCode = validDto();
        noCode.setCode(null);
        assertThatThrownBy(() -> projectAuditRuleService.createRule(1L, noCode, 10L))
                .hasMessage("规则代码不能为空");
    }

    @Test
    void createRule_duplicateCode_throwsException() {
        mockProjectExists();
        when(auditRuleMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> projectAuditRuleService.createRule(1L, validDto(), 10L))
                .isInstanceOf(BizException.class)
                .hasMessage("规则代码已存在");
    }

    @Test
    void createRule_invalidDrlSyntax_throwsException() {
        mockProjectExists();
        when(auditRuleMapper.selectCount(any())).thenReturn(0L);
        ProjectAuditRuleDTO dto = validDto();
        dto.setDroolsContent("invalid {{{");

        assertThatThrownBy(() -> projectAuditRuleService.createRule(1L, dto, 10L))
                .isInstanceOf(BizException.class)
                .hasMessage("规则语法无效");
        verify(auditRuleMapper, never()).insert(any(AuditRule.class));
    }

    @Test
    void createRule_fillsFieldsAndSavesInitialVersion() {
        mockProjectExists();
        when(auditRuleMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> {
            inv.getArgument(0, AuditRule.class).setId(1L);
            return 1;
        }).when(auditRuleMapper).insert(any(AuditRule.class));
        when(auditRuleVersionMapper.selectOne(any())).thenReturn(null);

        AuditRule result = projectAuditRuleService.createRule(1L, validDto(), 10L);

        ArgumentCaptor<AuditRule> ruleCaptor = ArgumentCaptor.forClass(AuditRule.class);
        verify(auditRuleMapper).insert(ruleCaptor.capture());
        AuditRule rule = ruleCaptor.getValue();
        assertThat(rule.getProjectId()).isEqualTo(1L);
        assertThat(rule.getCreateUserId()).isEqualTo(10L);
        assertThat(rule.getEnabled()).isEqualTo(1);
        assertThat(result.getSeverityDescription()).isEqualTo("高危");

        ArgumentCaptor<AuditRuleVersion> versionCaptor = ArgumentCaptor.forClass(AuditRuleVersion.class);
        verify(auditRuleVersionMapper).insert(versionCaptor.capture());
        AuditRuleVersion version = versionCaptor.getValue();
        assertThat(version.getRuleId()).isEqualTo(1L);
        assertThat(version.getVersion()).isEqualTo(1);
        assertThat(version.getChangeLog()).isEqualTo("项目规则初始版本");
    }

    @Test
    void createRule_explicitlyDisabled_enabledIsZero() {
        mockProjectExists();
        when(auditRuleMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> {
            inv.getArgument(0, AuditRule.class).setId(1L);
            return 1;
        }).when(auditRuleMapper).insert(any(AuditRule.class));
        when(auditRuleVersionMapper.selectOne(any())).thenReturn(null);
        ProjectAuditRuleDTO dto = validDto();
        dto.setEnabled(false);

        projectAuditRuleService.createRule(1L, dto, 10L);

        ArgumentCaptor<AuditRule> captor = ArgumentCaptor.forClass(AuditRule.class);
        verify(auditRuleMapper).insert(captor.capture());
        assertThat(captor.getValue().getEnabled()).isZero();
    }

    @Test
    void createRule_versionIncrementsFromLastVersion() {
        mockProjectExists();
        when(auditRuleMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> {
            inv.getArgument(0, AuditRule.class).setId(1L);
            return 1;
        }).when(auditRuleMapper).insert(any(AuditRule.class));
        AuditRuleVersion last = new AuditRuleVersion();
        last.setVersion(3);
        when(auditRuleVersionMapper.selectOne(any())).thenReturn(last);

        projectAuditRuleService.createRule(1L, validDto(), 10L);

        ArgumentCaptor<AuditRuleVersion> captor = ArgumentCaptor.forClass(AuditRuleVersion.class);
        verify(auditRuleVersionMapper).insert(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(4);
    }

    @Test
    void updateRule_ruleNotFound_throwsException() {
        mockProjectExists();
        when(auditRuleMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> projectAuditRuleService.updateRule(1L, 9L, validDto(), 10L))
                .isInstanceOf(BizException.class)
                .hasMessage("项目审核规则不存在");
    }

    @Test
    void updateRule_updatesOnlyProvidedNonNullFields() {
        mockProjectExists();
        AuditRule rule = existingRule();
        // 第一次取旧规则，更新后再取最新
        when(auditRuleMapper.selectOne(any())).thenReturn(rule, rule);
        ProjectAuditRuleDTO dto = new ProjectAuditRuleDTO();
        dto.setName("新名称");

        projectAuditRuleService.updateRule(1L, 1L, dto, 10L);

        ArgumentCaptor<AuditRule> captor = ArgumentCaptor.forClass(AuditRule.class);
        verify(auditRuleMapper).updateById(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("新名称");
        // 未传入字段保持不变
        assertThat(captor.getValue().getCode()).isEqualTo("PROJ_DELETE_CHECK");
    }

    @Test
    void updateRule_drlChangedWithInvalidSyntax_throwsException() {
        mockProjectExists();
        when(auditRuleMapper.selectOne(any())).thenReturn(existingRule());
        ProjectAuditRuleDTO dto = new ProjectAuditRuleDTO();
        dto.setDroolsContent("invalid {{{");

        assertThatThrownBy(() -> projectAuditRuleService.updateRule(1L, 1L, dto, 10L))
                .isInstanceOf(BizException.class)
                .hasMessage("规则语法无效");
        verify(auditRuleMapper, never()).updateById(any(AuditRule.class));
    }

    @Test
    void updateRule_drlChanged_savesVersion() {
        mockProjectExists();
        AuditRule rule = existingRule();
        when(auditRuleMapper.selectOne(any())).thenReturn(rule, rule);
        when(auditRuleVersionMapper.selectOne(any())).thenReturn(null);
        ProjectAuditRuleDTO dto = new ProjectAuditRuleDTO();
        dto.setDroolsContent(VALID_DRL.replace("HIGH", "CRITICAL"));

        projectAuditRuleService.updateRule(1L, 1L, dto, 10L);

        ArgumentCaptor<AuditRuleVersion> captor = ArgumentCaptor.forClass(AuditRuleVersion.class);
        verify(auditRuleVersionMapper).insert(captor.capture());
        assertThat(captor.getValue().getChangeLog()).isEqualTo("项目规则更新");
    }

    @Test
    void updateRule_suggestionOnlyChanged_savesVersion() {
        mockProjectExists();
        AuditRule rule = existingRule();
        when(auditRuleMapper.selectOne(any())).thenReturn(rule, rule);
        when(auditRuleVersionMapper.selectOne(any())).thenReturn(null);
        ProjectAuditRuleDTO dto = new ProjectAuditRuleDTO();
        dto.setSuggestion("新的修复建议");

        projectAuditRuleService.updateRule(1L, 1L, dto, 10L);

        verify(auditRuleVersionMapper).insert(any(AuditRuleVersion.class));
    }

    @Test
    void updateRule_noContentChange_skipsVersionSave() {
        mockProjectExists();
        AuditRule rule = existingRule();
        when(auditRuleMapper.selectOne(any())).thenReturn(rule, rule);
        ProjectAuditRuleDTO dto = new ProjectAuditRuleDTO();
        dto.setName("仅改名称");

        projectAuditRuleService.updateRule(1L, 1L, dto, 10L);

        verify(auditRuleVersionMapper, never()).insert(any(AuditRuleVersion.class));
    }

    @Test
    void deleteRule_checksOwnershipThenDeletes() {
        mockProjectExists();
        when(auditRuleMapper.selectOne(any())).thenReturn(existingRule());

        projectAuditRuleService.deleteRule(1L, 1L);

        verify(auditRuleMapper).deleteById(1L);
    }

    @Test
    void deleteRule_ruleNotFound_throwsException() {
        mockProjectExists();
        when(auditRuleMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> projectAuditRuleService.deleteRule(1L, 9L))
                .isInstanceOf(BizException.class)
                .hasMessage("项目审核规则不存在");
    }

    @Test
    void toggleRule_enable_validatesDrl() {
        mockProjectExists();
        when(auditRuleMapper.selectOne(any())).thenReturn(existingRule(), existingRule());

        projectAuditRuleService.toggleRule(1L, 1L, true, 10L);

        verify(auditRuleMapper).updateById(any(AuditRule.class));
    }

    @Test
    void toggleRule_enableWithInvalidSyntax_throwsException() {
        mockProjectExists();
        AuditRule rule = existingRule();
        rule.setDroolsContent("invalid {{{");
        when(auditRuleMapper.selectOne(any())).thenReturn(rule);

        assertThatThrownBy(() -> projectAuditRuleService.toggleRule(1L, 1L, true, 10L))
                .isInstanceOf(BizException.class)
                .hasMessage("规则语法无效");
    }

    @Test
    void toggleRule_disable_updatesWithoutDrlValidation() {
        mockProjectExists();
        AuditRule invalid = existingRule();
        invalid.setDroolsContent("invalid {{{");
        when(auditRuleMapper.selectOne(any())).thenReturn(invalid, invalid);

        projectAuditRuleService.toggleRule(1L, 1L, false, 10L);

        verify(auditRuleMapper).updateById(any(AuditRule.class));
        assertThat(invalid.getEnabled()).isZero();
    }
}
