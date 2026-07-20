package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class ProjectOverviewIssueRectificationVO {

    private Long projectId;

    private Long latestVersionId;

    private Long previousVersionId;

    private Integer latestIssueSqlCount;

    private Integer previousIssueSqlCount;

    private Integer changeCount;

    private BigDecimal rectificationRate;

    private BigDecimal increaseRate;

    private BigDecimal decreaseRate;

    private String trend;
}
