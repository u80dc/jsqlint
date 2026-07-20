package cn.jnu.zhangcs.jsqlint.server.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class InspectionConfigDTO {

    @NotBlank(message = "执行调度规则不能为空")
    private String cronExpr;

    private Boolean enabled;
}
