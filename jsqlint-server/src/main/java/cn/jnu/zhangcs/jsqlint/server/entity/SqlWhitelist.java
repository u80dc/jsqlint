package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SQL 白名单实体
 * <p>
 * 白名单绑定项目、SQL 指纹和审核规则，审核时命中白名单的结果将被忽略。
 *
 * @author zhangcs
 */
@Data
@TableName("jsl_sql_whitelist")
public class SqlWhitelist {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 项目ID
     */
    private Long projectId;

    /**
     * SQL 指纹（SHA-256，64 字符十六进制）
     */
    private String sqlFingerprint;

    /**
     * 审核规则ID
     */
    private Long ruleId;

    /**
     * 示例 SQL（供用户辨识，非用于匹配）
     */
    private String sqlSample;

    /**
     * 加白原因
     */
    private String reason;

    /**
     * 创建人ID
     */
    private Long createUserId;

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

    /**
     * 规则名称（非持久化）
     */
    @TableField(exist = false)
    private String ruleName;

    /**
     * 创建人名称（非持久化）
     */
    @TableField(exist = false)
    private String createUserName;
}
