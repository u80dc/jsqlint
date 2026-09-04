package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.SystemConfig;
import cn.jnu.zhangcs.jsqlint.server.mapper.SystemConfigMapper;
import cn.jnu.zhangcs.jsqlint.server.model.dto.SystemSettingsDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SystemSettingsVO;
import cn.jnu.zhangcs.jsqlint.server.service.SystemConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SystemConfigServiceImpl implements SystemConfigService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final SystemConfigMapper mapper;

    @Override
    public SystemSettingsVO getSettings() {
        return toSettingsVO(loadConfigMap());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SystemSettingsVO saveSettings(SystemSettingsDTO dto, Long operatorId) {
        SystemSettingsDTO settings = dto == null ? new SystemSettingsDTO() : dto;
        Map<String, SystemConfig> existingConfigs = loadConfigMap();

        SystemSettingsDTO.BasicSettings basic = basic(settings);
        upsert(existingConfigs, ConfigItem.THEME_COLOR, stringValue(basic.getThemeColor(), ConfigItem.THEME_COLOR), operatorId);
        upsert(existingConfigs, ConfigItem.LANGUAGE, stringValue(basic.getLanguage(), ConfigItem.LANGUAGE), operatorId);
        upsert(existingConfigs, ConfigItem.PLATFORM_NAME, stringValue(basic.getPlatformName(), ConfigItem.PLATFORM_NAME), operatorId);

        SystemSettingsDTO.MessageNotificationSettings messageNotification = messageNotification(settings);
        upsert(existingConfigs, ConfigItem.NOTIFY_NEW_PROJECT, booleanValue(messageNotification.getNotifyNewProject(), ConfigItem.NOTIFY_NEW_PROJECT), operatorId);
        upsert(existingConfigs, ConfigItem.NOTIFY_REMOVE_PROJECT, booleanValue(messageNotification.getNotifyRemoveProject(), ConfigItem.NOTIFY_REMOVE_PROJECT), operatorId);
        upsert(existingConfigs, ConfigItem.NOTIFY_AUDIT_COMPLETE, booleanValue(messageNotification.getNotifyAuditComplete(), ConfigItem.NOTIFY_AUDIT_COMPLETE), operatorId);
        upsert(existingConfigs, ConfigItem.NOTIFY_HIGH_RISK_SQL, booleanValue(messageNotification.getNotifyHighRiskSql(), ConfigItem.NOTIFY_HIGH_RISK_SQL), operatorId);
        upsert(existingConfigs, ConfigItem.EMAIL_ENABLED, booleanValue(messageNotification.getEmailEnabled(), ConfigItem.EMAIL_ENABLED), operatorId);
        upsert(existingConfigs, ConfigItem.SMTP_HOST, stringValue(messageNotification.getHost(), ConfigItem.SMTP_HOST), operatorId);
        upsert(existingConfigs, ConfigItem.SMTP_PORT, integerValue(messageNotification.getPort(), ConfigItem.SMTP_PORT), operatorId);
        upsert(existingConfigs, ConfigItem.SMTP_USE_SSL, booleanValue(messageNotification.getUseSsl(), ConfigItem.SMTP_USE_SSL), operatorId);
        upsert(existingConfigs, ConfigItem.SENDER_EMAIL, stringValue(messageNotification.getSenderEmail(), ConfigItem.SENDER_EMAIL), operatorId);
        upsert(existingConfigs, ConfigItem.SENDER_NAME, stringValue(messageNotification.getSenderName(), ConfigItem.SENDER_NAME), operatorId);
        upsert(existingConfigs, ConfigItem.SMTP_USERNAME, stringValue(messageNotification.getUsername(), ConfigItem.SMTP_USERNAME), operatorId);
        upsert(existingConfigs, ConfigItem.SMTP_PASSWORD, stringValue(messageNotification.getPassword(), ConfigItem.SMTP_PASSWORD), operatorId);
        upsert(existingConfigs, ConfigItem.NOTIFICATION_TEMPLATE, stringValue(messageNotification.getNotificationTemplate(), ConfigItem.NOTIFICATION_TEMPLATE), operatorId);
        upsert(existingConfigs, ConfigItem.RECIPIENT_RULES, listValue(messageNotification.getRecipientRules(), ConfigItem.RECIPIENT_RULES), operatorId);

        SystemSettingsDTO.ProjectAnalysisSettings projectAnalysis = projectAnalysis(settings);
        upsert(existingConfigs, ConfigItem.ANALYSIS_TASK_TIMEOUT_MINUTES, integerValue(projectAnalysis.getAnalysisTaskTimeoutMinutes(), ConfigItem.ANALYSIS_TASK_TIMEOUT_MINUTES), operatorId);
        upsert(existingConfigs, ConfigItem.COMPILE_TASK_TIMEOUT_MINUTES, integerValue(projectAnalysis.getCompileTaskTimeoutMinutes(), ConfigItem.COMPILE_TASK_TIMEOUT_MINUTES), operatorId);
        upsert(existingConfigs, ConfigItem.MAX_CONCURRENT_TASKS, integerValue(projectAnalysis.getMaxConcurrentTasks(), ConfigItem.MAX_CONCURRENT_TASKS), operatorId);
        upsert(existingConfigs, ConfigItem.REPORT_RETENTION_COUNT, integerValue(projectAnalysis.getReportRetentionCount(), ConfigItem.REPORT_RETENTION_COUNT), operatorId);
        upsert(existingConfigs, ConfigItem.RUNNING_TASK_TIMEOUT_MINUTES, integerValue(projectAnalysis.getRunningTaskTimeoutMinutes(), ConfigItem.RUNNING_TASK_TIMEOUT_MINUTES), operatorId);
        upsert(existingConfigs, ConfigItem.HIGH_RISK_ALERT_ENABLED, booleanValue(projectAnalysis.getHighRiskAlertEnabled(), ConfigItem.HIGH_RISK_ALERT_ENABLED), operatorId);
        upsert(existingConfigs, ConfigItem.ALLOW_NORMAL_USER_CREATE_PROJECT, booleanValue(projectAnalysis.getAllowNormalUserCreateProject(), ConfigItem.ALLOW_NORMAL_USER_CREATE_PROJECT), operatorId);

        SystemSettingsDTO.OperationLogSettings operationLog = operationLog(settings);
        upsert(existingConfigs, ConfigItem.OPERATION_LOG_RETENTION_DAYS, integerValue(operationLog.getOperationLogRetentionDays(), ConfigItem.OPERATION_LOG_RETENTION_DAYS), operatorId);
        upsert(existingConfigs, ConfigItem.OPERATION_LOG_ENABLED, booleanValue(operationLog.getOperationLogEnabled(), ConfigItem.OPERATION_LOG_ENABLED), operatorId);
        upsert(existingConfigs, ConfigItem.LOGIN_LOG_ENABLED, booleanValue(operationLog.getLoginLogEnabled(), ConfigItem.LOGIN_LOG_ENABLED), operatorId);
        upsert(existingConfigs, ConfigItem.AUDIT_EXPORT_ENABLED, booleanValue(operationLog.getAuditExportEnabled(), ConfigItem.AUDIT_EXPORT_ENABLED), operatorId);

        return getSettings();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SystemSettingsVO resetSettings(Long operatorId) {
        Map<String, SystemConfig> existingConfigs = loadConfigMap();
        for (ConfigItem item : ConfigItem.values()) {
            upsert(existingConfigs, item, item.defaultValue, operatorId);
        }
        return getSettings();
    }

    private SystemSettingsVO toSettingsVO(Map<String, SystemConfig> configs) {
        return SystemSettingsVO.builder()
                .basic(SystemSettingsVO.BasicSettings.builder()
                        .themeColor(getString(configs, ConfigItem.THEME_COLOR))
                        .language(getString(configs, ConfigItem.LANGUAGE))
                        .platformName(getString(configs, ConfigItem.PLATFORM_NAME))
                        .build())
                .messageNotification(SystemSettingsVO.MessageNotificationSettings.builder()
                        .notifyNewProject(getBoolean(configs, ConfigItem.NOTIFY_NEW_PROJECT))
                        .notifyRemoveProject(getBoolean(configs, ConfigItem.NOTIFY_REMOVE_PROJECT))
                        .notifyAuditComplete(getBoolean(configs, ConfigItem.NOTIFY_AUDIT_COMPLETE))
                        .notifyHighRiskSql(getBoolean(configs, ConfigItem.NOTIFY_HIGH_RISK_SQL))
                        .emailEnabled(getBoolean(configs, ConfigItem.EMAIL_ENABLED))
                        .host(getString(configs, ConfigItem.SMTP_HOST))
                        .port(getInteger(configs, ConfigItem.SMTP_PORT))
                        .useSsl(getBoolean(configs, ConfigItem.SMTP_USE_SSL))
                        .senderEmail(getString(configs, ConfigItem.SENDER_EMAIL))
                        .senderName(getString(configs, ConfigItem.SENDER_NAME))
                        .username(getString(configs, ConfigItem.SMTP_USERNAME))
                        .password(getString(configs, ConfigItem.SMTP_PASSWORD))
                        .notificationTemplate(getString(configs, ConfigItem.NOTIFICATION_TEMPLATE))
                        .recipientRules(getStringList(configs, ConfigItem.RECIPIENT_RULES))
                        .build())
                .projectAnalysis(SystemSettingsVO.ProjectAnalysisSettings.builder()
                        .analysisTaskTimeoutMinutes(getInteger(configs, ConfigItem.ANALYSIS_TASK_TIMEOUT_MINUTES))
                        .compileTaskTimeoutMinutes(getInteger(configs, ConfigItem.COMPILE_TASK_TIMEOUT_MINUTES))
                        .maxConcurrentTasks(getInteger(configs, ConfigItem.MAX_CONCURRENT_TASKS))
                        .reportRetentionCount(getInteger(configs, ConfigItem.REPORT_RETENTION_COUNT))
                        .runningTaskTimeoutMinutes(getInteger(configs, ConfigItem.RUNNING_TASK_TIMEOUT_MINUTES))
                        .highRiskAlertEnabled(getBoolean(configs, ConfigItem.HIGH_RISK_ALERT_ENABLED))
                        .allowNormalUserCreateProject(getBoolean(configs, ConfigItem.ALLOW_NORMAL_USER_CREATE_PROJECT))
                        .build())
                .operationLog(SystemSettingsVO.OperationLogSettings.builder()
                        .operationLogRetentionDays(getInteger(configs, ConfigItem.OPERATION_LOG_RETENTION_DAYS))
                        .operationLogEnabled(getBoolean(configs, ConfigItem.OPERATION_LOG_ENABLED))
                        .loginLogEnabled(getBoolean(configs, ConfigItem.LOGIN_LOG_ENABLED))
                        .auditExportEnabled(getBoolean(configs, ConfigItem.AUDIT_EXPORT_ENABLED))
                        .build())
                .build();
    }

    private Map<String, SystemConfig> loadConfigMap() {
        LambdaQueryWrapper<SystemConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(SystemConfig::getConfigGroup, SystemConfig::getConfigKey);
        return mapper.selectList(wrapper).stream()
                .collect(Collectors.toMap(this::configId, Function.identity(), (left, right) -> right));
    }

    private void upsert(Map<String, SystemConfig> existingConfigs, ConfigItem item, String configValue, Long operatorId) {
        SystemConfig config = existingConfigs.get(configId(item.group, item.key));
        if (config == null) {
            config = new SystemConfig();
            config.setConfigGroup(item.group);
            config.setConfigKey(item.key);
            config.setValueType(item.valueType);
            config.setDescription(item.description);
            config.setConfigValue(configValue);
            config.setOperatorId(operatorId);
            mapper.insert(config);
            existingConfigs.put(configId(config), config);
            return;
        }

        config.setConfigValue(configValue);
        config.setValueType(item.valueType);
        config.setDescription(item.description);
        config.setOperatorId(operatorId);
        mapper.updateById(config);
    }

    private String getString(Map<String, SystemConfig> configs, ConfigItem item) {
        SystemConfig config = configs.get(configId(item.group, item.key));
        return config == null || config.getConfigValue() == null ? item.defaultValue : config.getConfigValue();
    }

    private Integer getInteger(Map<String, SystemConfig> configs, ConfigItem item) {
        String value = getString(configs, item);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return Integer.parseInt(item.defaultValue);
        }
    }

    private Boolean getBoolean(Map<String, SystemConfig> configs, ConfigItem item) {
        String value = getString(configs, item);
        return "1".equals(value) || Boolean.parseBoolean(value);
    }

    private List<String> getStringList(Map<String, SystemConfig> configs, ConfigItem item) {
        String value = getString(configs, item);
        if (value == null || value.isBlank()) {
            return Collections.emptyList();
        }
        if (!value.trim().startsWith("[")) {
            return Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(rule -> !rule.isEmpty())
                    .toList();
        }
        try {
            return JSON.readValue(value, STRING_LIST_TYPE);
        } catch (JacksonException e) {
            return getStringList(Collections.emptyMap(), item);
        }
    }

    private String stringValue(String value, ConfigItem item) {
        return value == null ? item.defaultValue : value;
    }

    private String integerValue(Integer value, ConfigItem item) {
        return String.valueOf(value == null ? Integer.parseInt(item.defaultValue) : value);
    }

    private String booleanValue(Boolean value, ConfigItem item) {
        return String.valueOf(value == null ? Boolean.parseBoolean(item.defaultValue) : value);
    }

    private String listValue(List<String> value, ConfigItem item) {
        List<String> rules = value == null ? getStringList(Collections.emptyMap(), item) : value;
        try {
            return JSON.writeValueAsString(rules);
        } catch (JacksonException e) {
            throw new BizException("通知接收人规则序列化失败", e);
        }
    }

    private SystemSettingsDTO.BasicSettings basic(SystemSettingsDTO settings) {
        return settings.getBasic() == null ? new SystemSettingsDTO.BasicSettings() : settings.getBasic();
    }

    private SystemSettingsDTO.MessageNotificationSettings messageNotification(SystemSettingsDTO settings) {
        return settings.getMessageNotification() == null
                ? new SystemSettingsDTO.MessageNotificationSettings()
                : settings.getMessageNotification();
    }

    private SystemSettingsDTO.ProjectAnalysisSettings projectAnalysis(SystemSettingsDTO settings) {
        return settings.getProjectAnalysis() == null
                ? new SystemSettingsDTO.ProjectAnalysisSettings()
                : settings.getProjectAnalysis();
    }

    private SystemSettingsDTO.OperationLogSettings operationLog(SystemSettingsDTO settings) {
        return settings.getOperationLog() == null
                ? new SystemSettingsDTO.OperationLogSettings()
                : settings.getOperationLog();
    }

    private String configId(SystemConfig config) {
        return configId(config.getConfigGroup(), config.getConfigKey());
    }

    private String configId(String group, String key) {
        return group + "." + key;
    }

    private enum ConfigItem {
        THEME_COLOR("basic", "themeColor", "STRING", "blue", "主题色"),
        LANGUAGE("basic", "language", "STRING", "zh-CN", "语言"),
        PLATFORM_NAME("basic", "platformName", "STRING", "SQL质量管理平台", "平台名称"),

        NOTIFY_NEW_PROJECT("message_notification", "notifyNewProject", "BOOLEAN", "true", "新增项目站内消息开关"),
        NOTIFY_REMOVE_PROJECT("message_notification", "notifyRemoveProject", "BOOLEAN", "true", "移除项目站内消息开关"),
        NOTIFY_AUDIT_COMPLETE("message_notification", "notifyAuditComplete", "BOOLEAN", "true", "审核完成站内消息开关"),
        NOTIFY_HIGH_RISK_SQL("message_notification", "notifyHighRiskSql", "BOOLEAN", "true", "高危SQL站内消息开关"),
        EMAIL_ENABLED("message_notification", "emailEnabled", "BOOLEAN", "true", "邮件通知开关"),
        SMTP_HOST("message_notification", "host", "STRING", "", "SMTP服务器地址"),
        SMTP_PORT("message_notification", "port", "INTEGER", "465", "SMTP端口"),
        SMTP_USE_SSL("message_notification", "useSsl", "BOOLEAN", "true", "SMTP是否启用SSL"),
        SENDER_EMAIL("message_notification", "senderEmail", "STRING", "", "发件人邮箱"),
        SENDER_NAME("message_notification", "senderName", "STRING", "SQL治理中心", "发件人名称"),
        SMTP_USERNAME("message_notification", "username", "STRING", "", "SMTP认证账号"),
        SMTP_PASSWORD("message_notification", "password", "STRING", "", "SMTP认证密码或授权码"),
        NOTIFICATION_TEMPLATE("message_notification", "notificationTemplate", "STRING", "项目 ${projectName} 的 ${taskType} 已完成，风险级别：${riskLevel}，问题数量：${issueCount}。", "通知模板"),
        RECIPIENT_RULES("message_notification", "recipientRules", "JSON", "[\"PROJECT_CREATOR\",\"PROJECT_ADMINS\"]", "通知接收人规则"),

        ANALYSIS_TASK_TIMEOUT_MINUTES("project_analysis", "analysisTaskTimeoutMinutes", "INTEGER", "60", "分析任务超时时间，单位分钟"),
        COMPILE_TASK_TIMEOUT_MINUTES("project_analysis", "compileTaskTimeoutMinutes", "INTEGER", "30", "编译任务超时时间，单位分钟"),
        MAX_CONCURRENT_TASKS("project_analysis", "maxConcurrentTasks", "INTEGER", "4", "最大并发任务数"),
        REPORT_RETENTION_COUNT("project_analysis", "reportRetentionCount", "INTEGER", "20", "报告保留数量"),
        RUNNING_TASK_TIMEOUT_MINUTES("project_analysis", "runningTaskTimeoutMinutes", "INTEGER", "60", "执行中任务超时回收时间，单位分钟"),
        HIGH_RISK_ALERT_ENABLED("project_analysis", "highRiskAlertEnabled", "BOOLEAN", "true", "是否启用高危问题告警"),
        ALLOW_NORMAL_USER_CREATE_PROJECT("project_analysis", "allowNormalUserCreateProject", "BOOLEAN", "true", "是否允许普通用户创建项目"),

        OPERATION_LOG_RETENTION_DAYS("operation_log", "operationLogRetentionDays", "INTEGER", "180", "操作日志保留周期，单位天"),
        OPERATION_LOG_ENABLED("operation_log", "operationLogEnabled", "BOOLEAN", "true", "操作日志开关"),
        LOGIN_LOG_ENABLED("operation_log", "loginLogEnabled", "BOOLEAN", "true", "登录日志开关"),
        AUDIT_EXPORT_ENABLED("operation_log", "auditExportEnabled", "BOOLEAN", "true", "审核导出开关");

        private final String group;
        private final String key;
        private final String valueType;
        private final String defaultValue;
        private final String description;

        ConfigItem(String group, String key, String valueType, String defaultValue, String description) {
            this.group = group;
            this.key = key;
            this.valueType = valueType;
            this.defaultValue = defaultValue;
            this.description = description;
        }
    }
}
