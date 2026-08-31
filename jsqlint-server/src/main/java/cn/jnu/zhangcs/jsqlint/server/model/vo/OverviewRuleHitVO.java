package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 规则命中 TOP N 统计
 *
 * @author zhangcs
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverviewRuleHitVO {

    /**
     * 总命中次数
     */
    private Integer totalHitCount;

    /**
     * 高频命中规则列表（按命中次数降序）
     */
    private List<RuleHitCountVO> topRules;

    /**
     * 规则命中计数
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuleHitCountVO {

        /**
         * 规则ID（null 表示未知规则）
         */
        private Long ruleId;

        /**
         * 规则名称
         */
        private String ruleName;

        /**
         * 规则代码
         */
        private String ruleCode;

        /**
         * 严重级别
         */
        private String severity;

        /**
         * 命中次数
         */
        private Integer hitCount;
    }
}
