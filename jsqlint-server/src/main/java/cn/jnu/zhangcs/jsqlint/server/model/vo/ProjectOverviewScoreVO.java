package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProjectOverviewScoreVO {

    private Long projectId;

    private Long versionId;

    private Integer totalSqlCount;

    private Integer issueSqlCount;

    private Integer securitySqlCount;

    private Integer performanceSqlCount;

    private Integer qualitySqlCount;

    private Integer otherSqlCount;

    private Integer score;

    private String rating;
}
