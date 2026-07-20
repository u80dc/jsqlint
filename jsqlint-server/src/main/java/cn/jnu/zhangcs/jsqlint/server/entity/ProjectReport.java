package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 项目审核报告汇总实体
 *
 * @author zhangcs
 */
@Data
@TableName(value = "jsl_project_report", autoResultMap = true)
public class ProjectReport {

    /**
     * 报告ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 项目ID
     */
    private Long projectId;

    /**
     * 版本ID
     */
    private Long versionId;

    /**
     * 生成报告任务ID
     */
    private Long taskId;

    /**
     * 项目质量得分（满分 100）
     */
    private Integer score;

    /**
     * 评级: S/A/B/C/D
     */
    private String rating;

    /**
     * SQL 总数
     */
    private Integer totalSqlCount;

    /**
     * 有问题的 SQL 数量
     */
    private Integer issueSqlCount;

    /**
     * 问题 SQL 占比（百分比）
     */
    private BigDecimal issueSqlRate;

    /**
     * 问题总数
     */
    private Integer issueCount;

    /**
     * 致命问题数
     */
    private Integer criticalCount;

    /**
     * 存在致命问题的 SQL 数量
     */
    private Integer criticalSqlCount;

    /**
     * 高危问题数
     */
    private Integer highCount;

    /**
     * 警告问题数
     */
    private Integer warningCount;

    /**
     * 信息问题数
     */
    private Integer infoCount;

    /**
     * 安全类问题 SQL 数量
     */
    private Integer securityIssueSqlCount;

    /**
     * 性能类问题 SQL 数量
     */
    private Integer performanceIssueSqlCount;

    /**
     * SQL 质量类问题 SQL 数量
     */
    private Integer qualityIssueSqlCount;

    /**
     * 其他类问题 SQL 数量
     */
    private Integer otherIssueSqlCount;

    /**
     * TOP 命中规则（JSON）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> topRuleJson;

    /**
     * TOP 问题文件（JSON）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> topFileJson;

    /**
     * 高危 SQL 明细（JSON）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> highRiskSqlJson;

    /**
     * 报告生成时间
     */
    private LocalDateTime generatedTime;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
