package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.entity.SystemConfig;
import cn.jnu.zhangcs.jsqlint.server.mapper.SystemConfigMapper;
import cn.jnu.zhangcs.jsqlint.server.model.dto.SystemSettingsDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SystemSettingsVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SystemConfigServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class SystemConfigServiceImplTest {

    @Mock
    private SystemConfigMapper mapper;

    @InjectMocks
    private SystemConfigServiceImpl configService;

    @Test
    void getSettings_noConfig_returnsAllDefaults() {
        when(mapper.selectList(any())).thenReturn(List.of());

        SystemSettingsVO vo = configService.getSettings();

        assertThat(vo.getBasic().getThemeColor()).isEqualTo("blue");
        assertThat(vo.getBasic().getLanguage()).isEqualTo("zh-CN");
        assertThat(vo.getMessageNotification().getNotifyNewProject()).isTrue();
        assertThat(vo.getMessageNotification().getPort()).isEqualTo(465);
        assertThat(vo.getMessageNotification().getSenderName()).isEqualTo("SQL治理中心");
        assertThat(vo.getMessageNotification().getRecipientRules())
                .containsExactly("PROJECT_CREATOR", "PROJECT_ADMINS");
        assertThat(vo.getProjectAnalysis().getAnalysisTaskTimeoutMinutes()).isEqualTo(60);
        assertThat(vo.getProjectAnalysis().getCompileTaskTimeoutMinutes()).isEqualTo(30);
        assertThat(vo.getProjectAnalysis().getMaxConcurrentTasks()).isEqualTo(4);
        assertThat(vo.getProjectAnalysis().getReportRetentionCount()).isEqualTo(20);
        assertThat(vo.getProjectAnalysis().getRunningTaskTimeoutMinutes()).isEqualTo(60);
        assertThat(vo.getOperationLog().getOperationLogRetentionDays()).isEqualTo(180);
        assertThat(vo.getOperationLog().getOperationLogEnabled()).isTrue();
    }

    @Test
    void getSettings_existingConfig_overridesDefaults() {
        when(mapper.selectList(any())).thenReturn(List.of(
                config("basic", "themeColor", "dark"),
                config("message_notification", "port", "587"),
                config("message_notification", "notifyNewProject", "false"),
                config("message_notification", "recipientRules", "[\"ADMIN\"]"),
                config("project_analysis", "maxConcurrentTasks", "8"),
                config("project_analysis", "runningTaskTimeoutMinutes", "120")));

        SystemSettingsVO vo = configService.getSettings();

        assertThat(vo.getBasic().getThemeColor()).isEqualTo("dark");
        assertThat(vo.getMessageNotification().getPort()).isEqualTo(587);
        assertThat(vo.getMessageNotification().getNotifyNewProject()).isFalse();
        assertThat(vo.getMessageNotification().getRecipientRules()).containsExactly("ADMIN");
        assertThat(vo.getProjectAnalysis().getMaxConcurrentTasks()).isEqualTo(8);
        assertThat(vo.getProjectAnalysis().getRunningTaskTimeoutMinutes()).isEqualTo(120);
        // 未配置项仍取默认值
        assertThat(vo.getBasic().getLanguage()).isEqualTo("zh-CN");
    }

    @Test
    void getSettings_invalidIntConfig_fallsBackToDefault() {
        when(mapper.selectList(any())).thenReturn(List.of(
                config("message_notification", "port", "abc")));

        SystemSettingsVO vo = configService.getSettings();

        assertThat(vo.getMessageNotification().getPort()).isEqualTo(465);
    }

    @Test
    void getSettings_commaSeparatedRecipientRules_split() {
        when(mapper.selectList(any())).thenReturn(List.of(
                config("message_notification", "recipientRules", " PROJECT_CREATOR , ADMIN , ")));

        SystemSettingsVO vo = configService.getSettings();

        assertThat(vo.getMessageNotification().getRecipientRules())
                .containsExactly("PROJECT_CREATOR", "ADMIN");
    }

    @Test
    void saveSettings_nullInput_insertsAllDefaults() {
        when(mapper.selectList(any())).thenReturn(List.of(), List.of());

        configService.saveSettings(null, 10L);

        // 首次保存：全部配置项走 insert，共 28 项
        verify(mapper, org.mockito.Mockito.times(28)).insert(any(SystemConfig.class));
        verify(mapper, never()).updateById(any(SystemConfig.class));
    }

    @Test
    void saveSettings_existingConfig_updated() {
        // saveSettings 内部先 loadConfigMap 判定插入/更新，saveSettings 末尾 getSettings 再 load 一次
        when(mapper.selectList(any())).thenReturn(
                List.of(config("basic", "themeColor", "blue")), List.of(), List.of());

        configService.saveSettings(null, 10L);

        // 仅 themeColor 已存在走更新，其余 27 项走插入
        verify(mapper, org.mockito.Mockito.times(1)).updateById(any(SystemConfig.class));
        verify(mapper, org.mockito.Mockito.times(27)).insert(any(SystemConfig.class));
    }

    @Test
    void saveSettings_writtenValuesReadableAfterSave() {
        // 第一次返回空列表（用于保存时判定插入），第二次返回保存后的配置
        when(mapper.selectList(any())).thenReturn(List.of(), List.of());
        ArgumentCaptor<SystemConfig> captor = ArgumentCaptor.forClass(SystemConfig.class);

        SystemSettingsDTO dto = new SystemSettingsDTO();
        SystemSettingsDTO.BasicSettings basic = new SystemSettingsDTO.BasicSettings();
        basic.setThemeColor("dark");
        dto.setBasic(basic);
        configService.saveSettings(dto, 10L);

        verify(mapper, org.mockito.Mockito.times(28)).insert(captor.capture());
        Map<String, String> saved = captor.getAllValues().stream()
                .collect(Collectors.toMap(c -> c.getConfigGroup() + "." + c.getConfigKey(),
                        SystemConfig::getConfigValue));
        assertThat(saved.get("basic.themeColor")).isEqualTo("dark");
        // 未指定项写默认值
        assertThat(saved.get("basic.language")).isEqualTo("zh-CN");
        assertThat(saved.get("message_notification.port")).isEqualTo("465");
        assertThat(saved.get("project_analysis.runningTaskTimeoutMinutes")).isEqualTo("60");
    }

    @Test
    void resetSettings_allConfigRewrittenToDefaults() {
        when(mapper.selectList(any())).thenReturn(
                List.of(config("basic", "themeColor", "dark")), List.of(), List.of());

        SystemSettingsVO vo = configService.resetSettings(10L);

        assertThat(vo.getBasic().getThemeColor()).isEqualTo("blue");
        // 已存在 1 项走更新，其余 27 项走插入
        verify(mapper, org.mockito.Mockito.times(1)).updateById(any(SystemConfig.class));
        verify(mapper, org.mockito.Mockito.times(27)).insert(any(SystemConfig.class));
    }

    private SystemConfig config(String group, String key, String value) {
        SystemConfig config = new SystemConfig();
        config.setConfigGroup(group);
        config.setConfigKey(key);
        config.setConfigValue(value);
        return config;
    }
}
