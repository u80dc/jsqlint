package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectVersionVO {

    private Long id;

    private Long projectId;

    private String projectName;

    private String ref;

    private String commitId;

    private String commitMessage;

    private String author;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime operationTime;

    private Integer overallStatus;

    private String overallStatusName;

    private Integer currentStep;

    private String currentStepName;

    private String message;
}
