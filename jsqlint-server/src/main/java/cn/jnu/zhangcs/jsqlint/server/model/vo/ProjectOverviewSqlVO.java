package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ProjectOverviewSqlVO {

    private Long projectId;

    private Long versionId;

    private Integer totalSqlCount;

    private List<SqlTypeCountVO> typeCounts;

    @Data
    @Builder
    public static class SqlTypeCountVO {

        private String sqlType;

        private Integer count;
    }
}
