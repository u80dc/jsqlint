package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AlertInstanceVO {

    private Long id;

    private Long ruleId;

    private String ruleName;

    private Long projectId;

    private String projectName;

    private Long versionId;

    private Long taskId;

    private String title;

    private String content;

    private String severity;

    private String severityDescription;

    private String status;

    private String statusDescription;

    private Integer triggerCount;

    private LocalDateTime firstTriggeredAt;

    private LocalDateTime lastTriggeredAt;

    private LocalDateTime ackTime;

    private LocalDateTime resolvedTime;

    private LocalDateTime createTime;
}
