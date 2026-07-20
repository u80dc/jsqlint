package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class InspectionConfigVO {

    private Long id;

    private Long projectId;

    private String projectName;

    private String cronExpr;

    private Boolean enabled;

    private Long nextExecTime;

    private Long lastExecTime;

    private String lastStatus;

    private String lastMessage;

    private Long operatorId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
