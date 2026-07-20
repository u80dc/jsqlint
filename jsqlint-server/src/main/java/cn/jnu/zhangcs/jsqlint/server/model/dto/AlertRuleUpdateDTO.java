package cn.jnu.zhangcs.jsqlint.server.model.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class AlertRuleUpdateDTO {

    private String name;

    private Long projectId;

    private String severity;

    private String minSeverity;

    @Min(value = 1, message = "触发阈值必须大于0")
    private Integer thresholdCount;

    private Boolean notifyProjectMembers;

    private Boolean notifyCreator;

    private Boolean enabled;
}
