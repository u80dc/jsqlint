package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverviewProjectSummaryVO {

    private Integer totalProjects;

    private Integer safeSqlCount;

    private Integer warningSqlCount;

    private Integer dangerSqlCount;
}
