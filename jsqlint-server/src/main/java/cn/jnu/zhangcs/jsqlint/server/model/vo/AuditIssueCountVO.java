package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 审核问题计数 VO（按 SQL 语句分组，行内徽标用）
 *
 * @author zhangcs
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditIssueCountVO {

    /**
     * 扫描记录ID（关联 jsl_sql_statement）
     */
    private Long scanId;

    /**
     * 问题总数
     */
    private Long total;

    /**
     * 待处理（OPEN）问题数
     */
    private Long openCount;
}
