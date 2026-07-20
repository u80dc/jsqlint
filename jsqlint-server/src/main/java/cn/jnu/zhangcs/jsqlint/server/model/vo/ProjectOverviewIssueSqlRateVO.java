package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class ProjectOverviewIssueSqlRateVO {

    private Long projectId;

    private Long latestVersionId;

    private Long previousVersionId;

    private Integer latestTotalSqlCount;

    private Integer latestIssueSqlCount;

    private Integer previousIssueSqlCount;

    private BigDecimal issueSqlRate;

    private Integer changeCount;

    private BigDecimal increaseRate;

    private BigDecimal decreaseRate;

    private String trend;
}
