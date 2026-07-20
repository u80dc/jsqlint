package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProjectOverviewRuleVO {

    private Long projectId;

    private Long versionId;

    private Integer totalRuleCount;

    private Integer globalRuleCount;

    private Integer projectRuleCount;

    private Integer enabledRuleCount;

    private Integer enabledProjectRuleCount;
}
