package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ProjectStatusVO {

    private Long projectId;

    private Long versionId;

    private Integer overallStatus;

    private String overallStatusName;

    private Integer currentStep;

    private String currentStepName;

    private String message;

    private List<ProjectVersionStepVO> steps;
}
