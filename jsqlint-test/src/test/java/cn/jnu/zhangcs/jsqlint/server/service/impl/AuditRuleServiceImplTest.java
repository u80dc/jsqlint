package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditRule;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditRuleVersion;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditRuleMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditRuleVersionMapper;
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
 * {@link AuditRuleServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class AuditRuleServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        // wrapper.set(...) 需要实体列缓存，纯单元测试下需手动初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AuditRule.class);
    }

    // SqlAuditTool 会自行前置 package/import，规则内容本身不能带
    private static final String VALID_DRL = """
            rule "delete without where"
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

    @InjectMocks
    private AuditRuleServiceImpl ruleService;

    private AuditRule validRule() {
        AuditRule rule = new AuditRule();
        rule.setName("delete without where");
        rule.setCode("DELETE_WITHOUT_WHERE");
        rule.setDroolsContent(VALID_DRL);
        rule.setSeverity("high");
        rule.setCategory("SQL_QUALITY");
        rule.setSuggestion("为 DELETE 语句补充 WHERE 条件");
        return rule;
    }

    @Test
    void listRules_fillsSeverityAndCategoryDescription() {
        AuditRule rule = validRule();
        Page<AuditRule> page = new Page<>(1, 10);
        page.setRecords(List.of(rule));
        when(auditRuleMapper.selectPage(any(), any())).thenReturn(page);

        List<AuditRule> result = ruleService.listRules(1, 10, null, null, null);

        assertThat(result.get(0).getSeverityDescription()).isEqualTo("高危");
        assertThat(result.get(0).getCategoryDescription()).isEqualTo("SQL质量");
    }

    @Test
    void listEffectiveRules_delegatesToMapperAndFillsDescription() {
        AuditRule rule = validRule();
        rule.setSeverity("critical");
        rule.setCategory("SECURITY");
        when(auditRuleMapper.selectList(any())).thenReturn(List.of(rule));

        List<AuditRule> result = ruleService.listEffectiveRules(1L, true);

        assertThat(result.get(0).getSeverityDescription()).isEqualTo("致命");
        assertThat(result.get(0).getCategoryDescription()).isEqualTo("安全检查");
    }

    @Test
    void countRules_delegatesToMapper() {
        when(auditRuleMapper.selectCount(any())).thenReturn(7L);

        assertThat(ruleService.countRules(null, null, null)).isEqualTo(7L);
    }

    @Test
    void getRuleById_fillsDescription() {
        AuditRule rule = validRule();
        rule.setSeverity("warning");
        rule.setCategory("PERFORMANCE");
        when(auditRuleMapper.selectById(1L)).thenReturn(rule);

        AuditRule result = ruleService.getRuleById(1L);

        assertThat(result.getSeverityDescription()).isEqualTo("警告");
        assertThat(result.getCategoryDescription()).isEqualTo("性能优化");
    }

    @Test
    void getRuleById_notFound_returnsNull() {
        when(auditRuleMapper.selectById(9L)).thenReturn(null);

        assertThat(ruleService.getRuleById(9L)).isNull();
    }

    @Test
    void createRule_duplicateCode_throwsException() {
        when(auditRuleMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> ruleService.createRule(validRule(), 10L))
                .isInstanceOf(BizException.class)
                .hasMessage("规则代码已存在");
    }

    @Test
    void createRule_invalidDrlSyntax_throwsException() {
        when(auditRuleMapper.selectCount(any())).thenReturn(0L);
        AuditRule rule = validRule();
        rule.setDroolsContent("this is not a valid drools rule {{{");

        assertThatThrownBy(() -> ruleService.createRule(rule, 10L))
                .isInstanceOf(BizException.class)
                .hasMessage("规则语法无效");
        verify(auditRuleMapper, never()).insert(any(AuditRule.class));
    }

    @Test
    void createRule_fillsFieldsAndSavesInitialVersion() {
        when(auditRuleMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> {
            inv.getArgument(0, AuditRule.class).setId(1L);
            return 1;
        }).when(auditRuleMapper).insert(any(AuditRule.class));
        when(auditRuleVersionMapper.selectOne(any())).thenReturn(null);

        AuditRule result = ruleService.createRule(validRule(), 10L);

        assertThat(result.getCreateUserId()).isEqualTo(10L);
        assertThat(result.getEnabled()).isEqualTo(1);
        ArgumentCaptor<AuditRuleVersion> versionCaptor = ArgumentCaptor.forClass(AuditRuleVersion.class);
        verify(auditRuleVersionMapper).insert(versionCaptor.capture());
        AuditRuleVersion version = versionCaptor.getValue();
        assertThat(version.getRuleId()).isEqualTo(1L);
        assertThat(version.getVersion()).isEqualTo(1);
        assertThat(version.getChangeLog()).isEqualTo("初始版本");
        assertThat(version.getCreateUserId()).isEqualTo(10L);
    }

    @Test
    void createRule_versionIncrementsFromLastVersion() {
        when(auditRuleMapper.selectCount(any())).thenReturn(0L);
        doAnswer(inv -> {
            inv.getArgument(0, AuditRule.class).setId(1L);
            return 1;
        }).when(auditRuleMapper).insert(any(AuditRule.class));
        AuditRuleVersion last = new AuditRuleVersion();
        last.setVersion(3);
        when(auditRuleVersionMapper.selectOne(any())).thenReturn(last);

        ruleService.createRule(validRule(), 10L);

        ArgumentCaptor<AuditRuleVersion> captor = ArgumentCaptor.forClass(AuditRuleVersion.class);
        verify(auditRuleVersionMapper).insert(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(4);
    }

    @Test
    void updateRule_notFound_throwsException() {
        when(auditRuleMapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> ruleService.updateRule(9L, validRule()))
                .isInstanceOf(BizException.class)
                .hasMessage("规则不存在");
    }

    @Test
    void updateRule_drlChangedWithInvalidSyntax_throwsException() {
        AuditRule existing = validRule();
        when(auditRuleMapper.selectById(1L)).thenReturn(existing);
        AuditRule update = validRule();
        update.setDroolsContent("invalid {{{");

        assertThatThrownBy(() -> ruleService.updateRule(1L, update))
                .isInstanceOf(BizException.class)
                .hasMessage("规则语法无效");
    }

    @Test
    void updateRule_drlChanged_savesNewVersion() {
        AuditRule existing = validRule();
        existing.setCreateUserId(10L);
        existing.setEnabled(1);
        when(auditRuleMapper.selectById(1L)).thenReturn(existing);
        AuditRule update = validRule();
        update.setDroolsContent(VALID_DRL.replace("HIGH", "CRITICAL"));
        AuditRule latest = validRule();
        // selectById 被调用两次：先取 existing，更新后再取最新
        when(auditRuleMapper.selectById(1L)).thenReturn(existing, latest);

        AuditRule result = ruleService.updateRule(1L, update);

        verify(auditRuleVersionMapper).insert(any(AuditRuleVersion.class));
        assertThat(update.getId()).isEqualTo(1L);
        assertThat(update.getCreateUserId()).isEqualTo(10L);
        assertThat(result).isSameAs(latest);
    }

    @Test
    void updateRule_suggestionOnlyChange_skipsDrlValidationButSavesVersion() {
        AuditRule existing = validRule();
        existing.setCreateUserId(10L);
        when(auditRuleMapper.selectById(1L)).thenReturn(existing, existing);
        AuditRule update = validRule();
        update.setSuggestion("新的修复建议");

        ruleService.updateRule(1L, update);

        verify(auditRuleVersionMapper).insert(any(AuditRuleVersion.class));
    }

    @Test
    void updateRule_noContentChange_doesNotSaveVersion() {
        AuditRule existing = validRule();
        when(auditRuleMapper.selectById(1L)).thenReturn(existing, existing);
        AuditRule update = validRule();
        update.setDroolsContent(existing.getDroolsContent());
        update.setSuggestion(existing.getSuggestion());
        update.setName("仅改名称");

        ruleService.updateRule(1L, update);

        verify(auditRuleVersionMapper, never()).insert(any(AuditRuleVersion.class));
    }

    @Test
    void deleteRule_delegatesToMapper() {
        ruleService.deleteRule(1L);

        verify(auditRuleMapper).deleteById(1L);
    }

    @Test
    void toggleRule_enable_validatesDrlSyntax() {
        AuditRule rule = validRule();
        when(auditRuleMapper.selectById(1L)).thenReturn(rule);

        ruleService.toggleRule(1L, true);

        verify(auditRuleMapper).update(any(), any());
    }

    @Test
    void toggleRule_enable_ruleNotFound_throwsException() {
        when(auditRuleMapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> ruleService.toggleRule(9L, true))
                .isInstanceOf(BizException.class)
                .hasMessage("规则不存在");
    }

    @Test
    void toggleRule_enable_invalidSyntax_throwsException() {
        AuditRule rule = validRule();
        rule.setDroolsContent("invalid {{{");
        when(auditRuleMapper.selectById(1L)).thenReturn(rule);

        assertThatThrownBy(() -> ruleService.toggleRule(1L, true))
                .isInstanceOf(BizException.class)
                .hasMessage("规则语法无效，无法启用");
    }

    @Test
    void toggleRule_disable_updatesWithoutDrlValidation() {
        ruleService.toggleRule(1L, false);

        verify(auditRuleMapper, never()).selectById(any());
        verify(auditRuleMapper).update(any(), any());
    }

    @Test
    void validateRule_blankContent_returnsFalse() {
        assertThat(ruleService.validateRule(null)).isFalse();
        assertThat(ruleService.validateRule("   ")).isFalse();
    }

    @Test
    void validateRule_validDrl_returnsTrue() {
        assertThat(ruleService.validateRule(VALID_DRL)).isTrue();
    }

    @Test
    void validateRule_syntaxError_returnsFalse() {
        assertThat(ruleService.validateRule("invalid {{{")).isFalse();
    }

    @Test
    void reloadRules_validatesEnabledGlobalRules() {
        AuditRule rule = validRule();
        when(auditRuleMapper.selectList(any())).thenReturn(List.of(rule));

        ruleService.reloadRules();

        verify(auditRuleMapper).selectList(any());
    }
}
