package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectReportVO {

    private Long id;

    private Long projectId;

    private Long versionId;

    private Long taskId;

    private Integer score;

    private String rating;

    private Integer totalSqlCount;

    private Integer issueSqlCount;

    private BigDecimal issueSqlRate;

    private Integer issueCount;

    private Integer criticalCount;

    private Integer criticalSqlCount;

    private Integer highCount;

    private Integer warningCount;

    private Integer infoCount;

    private Integer securityIssueSqlCount;

    private Integer performanceIssueSqlCount;

    private Integer qualityIssueSqlCount;

    private Integer otherIssueSqlCount;

    private List<Map<String, Object>> topRuleJson;

    private List<Map<String, Object>> topFileJson;

    private List<Map<String, Object>> highRiskSqlJson;

    private LocalDateTime generatedTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
