package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * SQL 变更日志实体
 *
 * @author zhangcs
 */
@Data
@TableName(value = "jsl_sql_change_log", autoResultMap = true)
public class SqlChangeLog {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 项目ID
     */
    private Long projectId;

    /**
     * 源版本ID
     */
    private Long fromVersionId;

    /**
     * 目标版本ID
     */
    private Long toVersionId;

    /**
     * Mapper ID
     */
    private String statementId;

    /**
     * 变更类型: ADDED/MODIFIED/DELETED
     */
    private String changeType;

    /**
     * 变更前SQL hash
     */
    private String fromSqlHash;

    /**
     * 变更后SQL hash
     */
    private String toSqlHash;

    /**
     * 差异摘要（JSON）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> diffSummary;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
