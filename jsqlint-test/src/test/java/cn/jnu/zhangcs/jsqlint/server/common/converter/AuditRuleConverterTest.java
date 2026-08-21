package cn.jnu.zhangcs.jsqlint.server.common.converter;

import cn.jnu.zhangcs.jsqlint.server.entity.AuditRule;
import cn.jnu.zhangcs.jsqlint.server.model.dto.AuditRuleDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.AuditRuleVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuditRuleConverter} 单元测试。
 *
 * @author zhangcs
 */
class AuditRuleConverterTest {

    @Test
    void toVO_nullEntityReturnsNull() {
        assertThat(AuditRuleConverter.toVO(null, "张三")).isNull();
    }

    @Test
    void toVO_allFieldsCopied() {
        AuditRule rule = buildRule();

        AuditRuleVO vo = AuditRuleConverter.toVO(rule, "张三");

        assertThat(vo.getId()).isEqualTo(3L);
        assertThat(vo.getName()).isEqualTo("禁止SELECT *");
        assertThat(vo.getCode()).isEqualTo("NO_SELECT_ALL");
        assertThat(vo.getDescription()).isEqualTo("查询必须明确字段");
        assertThat(vo.getSeverity()).isEqualTo("high");
        assertThat(vo.getSeverityDescription()).isEqualTo("高");
        assertThat(vo.getCategory()).isEqualTo("SQL_QUALITY");
        assertThat(vo.getCategoryDescription()).isEqualTo("SQL质量");
        assertThat(vo.getProjectId()).isEqualTo(7L);
        assertThat(vo.getDroolsContent()).isEqualTo("rule \"no_select_all\"");
        assertThat(vo.getSuggestion()).isEqualTo("列出所需字段");
        assertThat(vo.getEnabled()).isEqualTo(1);
        assertThat(vo.getCreateUserName()).isEqualTo("张三");
        assertThat(vo.getCreateTime()).isEqualTo(LocalDateTime.of(2026, 1, 1, 9, 0));
        assertThat(vo.getUpdateTime()).isEqualTo(LocalDateTime.of(2026, 1, 2, 9, 0));
    }

    @Test
    void toEntity_nullDtoReturnsNull() {
        assertThat(AuditRuleConverter.toEntity(null)).isNull();
    }

    @Test
    void toEntity_mapsAllFields() {
        AuditRuleDTO dto = new AuditRuleDTO();
        dto.setName("禁止DELETE无WHERE");
        dto.setCode("NO_DELETE_WITHOUT_WHERE");
        dto.setDescription("DELETE必须带WHERE");
        dto.setSeverity("critical");
        dto.setCategory("SECURITY");
        dto.setDroolsContent("rule \"x\"");
        dto.setSuggestion("补充WHERE条件");

        AuditRule rule = AuditRuleConverter.toEntity(dto);

        assertThat(rule.getName()).isEqualTo("禁止DELETE无WHERE");
        assertThat(rule.getCode()).isEqualTo("NO_DELETE_WITHOUT_WHERE");
        assertThat(rule.getDescription()).isEqualTo("DELETE必须带WHERE");
        assertThat(rule.getSeverity()).isEqualTo("critical");
        assertThat(rule.getCategory()).isEqualTo("SECURITY");
        assertThat(rule.getDroolsContent()).isEqualTo("rule \"x\"");
        assertThat(rule.getSuggestion()).isEqualTo("补充WHERE条件");
    }

    @Test
    void updateEntity_overwritesEditableFields() {
        AuditRule rule = buildRule();

        AuditRuleDTO dto = new AuditRuleDTO();
        dto.setName("新名称");
        dto.setDescription("新描述");
        dto.setSeverity("info");
        dto.setCategory("PERFORMANCE");
        dto.setDroolsContent("rule \"new\"");
        dto.setSuggestion("新建议");
        AuditRuleConverter.updateEntity(rule, dto);

        assertThat(rule.getName()).isEqualTo("新名称");
        assertThat(rule.getDescription()).isEqualTo("新描述");
        assertThat(rule.getSeverity()).isEqualTo("info");
        assertThat(rule.getCategory()).isEqualTo("PERFORMANCE");
        assertThat(rule.getDroolsContent()).isEqualTo("rule \"new\"");
        assertThat(rule.getSuggestion()).isEqualTo("新建议");
    }

    @Test
    void updateEntity_nullEntityThrowsNothing() {
        AuditRuleConverter.updateEntity(null, new AuditRuleDTO());
    }

    @Test
    void updateEntity_nullDtoLeavesEntityUnchanged() {
        AuditRule rule = buildRule();
        AuditRuleConverter.updateEntity(rule, null);

        assertThat(rule.getName()).isEqualTo("禁止SELECT *");
        assertThat(rule.getSeverity()).isEqualTo("high");
    }

    private AuditRule buildRule() {
        AuditRule rule = new AuditRule();
        rule.setId(3L);
        rule.setName("禁止SELECT *");
        rule.setCode("NO_SELECT_ALL");
        rule.setDescription("查询必须明确字段");
        rule.setSeverity("high");
        rule.setSeverityDescription("高");
        rule.setCategory("SQL_QUALITY");
        rule.setCategoryDescription("SQL质量");
        rule.setProjectId(7L);
        rule.setDroolsContent("rule \"no_select_all\"");
        rule.setSuggestion("列出所需字段");
        rule.setEnabled(1);
        rule.setCreateTime(LocalDateTime.of(2026, 1, 1, 9, 0));
        rule.setUpdateTime(LocalDateTime.of(2026, 1, 2, 9, 0));
        return rule;
    }
}
