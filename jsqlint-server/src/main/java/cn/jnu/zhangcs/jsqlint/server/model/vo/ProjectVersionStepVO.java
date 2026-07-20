package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ProjectVersionStepVO {

    private Long id;

    private Long projectId;

    private Long versionId;

    private Integer stepType;

    private String stepCode;

    private String stepName;

    private Integer stepOrder;

    private Integer status;

    private String statusName;

    private Integer completed;

    private Long taskId;

    private String errorMessage;

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
