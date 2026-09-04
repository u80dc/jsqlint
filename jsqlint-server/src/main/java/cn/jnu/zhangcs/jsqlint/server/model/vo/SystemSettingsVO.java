package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SystemSettingsVO {

    private BasicSettings basic;

    private MessageNotificationSettings messageNotification;

    private ProjectAnalysisSettings projectAnalysis;

    private OperationLogSettings operationLog;

    @Data
    @Builder
    public static class BasicSettings {

        private String themeColor;

        private String language;

        private String platformName;
    }

    @Data
    @Builder
    public static class MessageNotificationSettings {

        private Boolean notifyNewProject;

        private Boolean notifyRemoveProject;

        private Boolean notifyAuditComplete;

        private Boolean notifyHighRiskSql;

        private Boolean emailEnabled;

        private String host;

        private Integer port;

        private Boolean useSsl;

        private String senderEmail;

        private String senderName;

        private String username;

        private String password;

        private String notificationTemplate;

        private List<String> recipientRules;
    }

    @Data
    @Builder
    public static class ProjectAnalysisSettings {

        private Integer analysisTaskTimeoutMinutes;

        private Integer compileTaskTimeoutMinutes;

        private Integer maxConcurrentTasks;

        private Integer reportRetentionCount;

        private Integer runningTaskTimeoutMinutes;

        private Boolean highRiskAlertEnabled;

        private Boolean allowNormalUserCreateProject;
    }

    @Data
    @Builder
    public static class OperationLogSettings {

        private Integer operationLogRetentionDays;

        private Boolean operationLogEnabled;

        private Boolean loginLogEnabled;

        private Boolean auditExportEnabled;
    }
}
