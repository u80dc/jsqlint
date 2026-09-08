package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 版本 SQL 差异总览 VO（含风险增量聚合）。
 *
 * @author zhangcs
 */
@Data
public class VersionDiffVO {

    private Long projectId;

    private Long fromVersionId;

    private Long toVersionId;

    private String fromVersionLabel;

    private String toVersionLabel;

    /**
     * 变更明细（ADDED/MODIFIED/DELETED）
     */
    private List<SqlChangeLogVO> changes;

    /**
     * 计数汇总：added/modified/deleted
     */
    private int addedCount;

    private int modifiedCount;

    private int deletedCount;

    /**
     * 风险增量：新增/修改 SQL 在目标版本命中的问题，按严重级别计数
     * （critical/high/warning/info → 数量）
     */
    private int newCriticalCount;

    private int newHighCount;

    private int newWarningCount;

    private int newInfoCount;

    /**
     * 新增/修改 SQL 携带的问题总数（风险增量之和）
     */
    private int newIssueCount;
}
