package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class ProjectOverviewIssueSqlVO {

    private Long projectId;

    private Long versionId;

    private Integer totalSqlCount;

    private Integer issueSqlCount;

    private Integer issueCount;

    private BigDecimal issueSqlRate;
}
