package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 问题严重级别分布统计
 *
 * @author zhangcs
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverviewSeverityDistributionVO {

    /**
     * 总问题数
     */
    private Integer totalIssueCount;

    /**
     * critical 级别问题数
     */
    private Integer criticalCount;

    /**
     * high 级别问题数
     */
    private Integer highCount;

    /**
     * warning 级别问题数
     */
    private Integer warningCount;

    /**
     * info 级别问题数
     */
    private Integer infoCount;
}
