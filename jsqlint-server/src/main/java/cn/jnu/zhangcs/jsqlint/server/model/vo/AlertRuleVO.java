package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AlertRuleVO {

    private Long id;

    private String name;

    private String ruleType;

    private String ruleTypeDescription;

    private Long projectId;

    private String projectName;

    private String severity;

    private String severityDescription;

    private String minSeverity;

    private String minSeverityDescription;

    private Integer thresholdCount;

    private Boolean notifyProjectMembers;

    private Boolean notifyCreator;

    private Integer enabled;

    private Boolean enabledFlag;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
