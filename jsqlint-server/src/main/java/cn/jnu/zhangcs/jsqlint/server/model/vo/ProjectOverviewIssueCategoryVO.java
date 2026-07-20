package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProjectOverviewIssueCategoryVO {

    private Long projectId;

    private Long versionId;

    private Integer securitySqlCount;

    private Integer performanceSqlCount;

    private Integer qualitySqlCount;

    private Integer otherSqlCount;
}
