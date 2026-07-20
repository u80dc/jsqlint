package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ProjectOverviewSqlQualityTrendVO {

    private Long projectId;

    private List<TrendItemVO> items;

    @Data
    @Builder
    public static class TrendItemVO {

        private Long versionId;

        private String ref;

        private String commitId;

        private LocalDateTime operationTime;

        private Integer totalSqlCount;

        private Integer criticalSqlCount;

        private Integer highSqlCount;

        private Integer warningSqlCount;
    }
}
