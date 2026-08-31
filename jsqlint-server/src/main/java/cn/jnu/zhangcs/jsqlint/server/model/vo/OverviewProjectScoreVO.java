package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 项目健康度排行统计
 *
 * @author zhangcs
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverviewProjectScoreVO {

    /**
     * 项目评分列表（按评分升序，最差在前）
     */
    private List<ProjectScoreVO> projects;

    /**
     * 单项目评分
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProjectScoreVO {

        /**
         * 项目ID
         */
        private Long projectId;

        /**
         * 项目名称
         */
        private String projectName;

        /**
         * 质量评分（0-100）
         */
        private Integer score;

        /**
         * 评级（A/B/C/D）
         */
        private String rating;

        /**
         * 问题 SQL 数
         */
        private Integer issueSqlCount;
    }
}
