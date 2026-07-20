package cn.jnu.zhangcs.jsqlint.server.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AlertRuleDTO {

    @NotBlank(message = "告警规则名称不能为空")
    private String name;

    @NotBlank(message = "告警规则类型不能为空")
    private String ruleType;

    private Long projectId;

    private String severity = "warning";

    private String minSeverity = "warning";

    @Min(value = 1, message = "触发阈值必须大于0")
    private Integer thresholdCount = 1;

    private Boolean notifyProjectMembers = true;

    private Boolean notifyCreator = true;
}
