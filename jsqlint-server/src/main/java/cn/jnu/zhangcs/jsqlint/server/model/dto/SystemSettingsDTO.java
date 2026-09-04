package cn.jnu.zhangcs.jsqlint.server.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SystemSettingsDTO {

    @Valid
    private BasicSettings basic = new BasicSettings();

    @Valid
    private MessageNotificationSettings messageNotification = new MessageNotificationSettings();

    @Valid
    private ProjectAnalysisSettings projectAnalysis = new ProjectAnalysisSettings();

    @Valid
    private OperationLogSettings operationLog = new OperationLogSettings();

    @Data
    public static class BasicSettings {

        private String themeColor = "blue";

        private String language = "zh-CN";

        @jakarta.validation.constraints.Size(max = 50, message = "平台名称长度不能超过50个字符")
        private String platformName = "SQL质量管理平台";
    }

    @Data
    public static class MessageNotificationSettings {

        private Boolean notifyNewProject = true;

        private Boolean notifyRemoveProject = true;

        private Boolean notifyAuditComplete = true;

        private Boolean notifyHighRiskSql = true;

        private Boolean emailEnabled = true;

        private String host = "";

        @Min(value = 1, message = "SMTP端口不能小于1")
        @Max(value = 65535, message = "SMTP端口不能大于65535")
        private Integer port = 465;

        private Boolean useSsl = true;

        private String senderEmail = "";

        private String senderName = "SQL治理中心";

        private String username = "";

        private String password = "";

        private String notificationTemplate = "项目 ${projectName} 的 ${taskType} 已完成，风险级别：${riskLevel}，问题数量：${issueCount}。";

        private List<String> recipientRules = new ArrayList<>(List.of("PROJECT_CREATOR", "PROJECT_ADMINS"));
    }

    @Data
    public static class ProjectAnalysisSettings {

        @Min(value = 1, message = "分析任务超时时间不能小于1分钟")
        @Max(value = 1440, message = "分析任务超时时间不能大于1440分钟")
        private Integer analysisTaskTimeoutMinutes = 60;

        @Min(value = 1, message = "编译任务超时时间不能小于1分钟")
        @Max(value = 1440, message = "编译任务超时时间不能大于1440分钟")
        private Integer compileTaskTimeoutMinutes = 30;

        @Min(value = 1, message = "最大并发任务数不能小于1")
        @Max(value = 64, message = "最大并发任务数不能大于64")
        private Integer maxConcurrentTasks = 4;

        @Min(value = 1, message = "报告保留数量不能小于1")
        @Max(value = 500, message = "报告保留数量不能大于500")
        private Integer reportRetentionCount = 20;

        @Min(value = 5, message = "执行中任务超时回收时间不能小于5分钟")
        @Max(value = 1440, message = "执行中任务超时回收时间不能大于1440分钟")
        private Integer runningTaskTimeoutMinutes = 60;

        private Boolean highRiskAlertEnabled = true;

        private Boolean allowNormalUserCreateProject = true;
    }

    @Data
    public static class OperationLogSettings {

        @Min(value = 1, message = "操作日志保留周期不能小于1天")
        @Max(value = 3650, message = "操作日志保留周期不能大于3650天")
        private Integer operationLogRetentionDays = 180;

        private Boolean operationLogEnabled = true;

        private Boolean loginLogEnabled = true;

        private Boolean auditExportEnabled = true;
    }
}
