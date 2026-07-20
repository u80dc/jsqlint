package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ProjectOverviewRuleHitVO {

    private Long projectId;

    private Long versionId;

    private Integer totalHitCount;

    private List<RuleHitCountVO> highFrequencyRules;

    @Data
    @Builder
    public static class RuleHitCountVO {

        private Long ruleId;

        private String ruleName;

        private String ruleCode;

        private String severity;

        private String category;

        private Integer hitCount;
    }
}
