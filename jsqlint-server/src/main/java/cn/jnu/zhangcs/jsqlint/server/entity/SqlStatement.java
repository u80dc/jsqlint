package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * SQL 语句解析结果实体
 *
 * @author zhangcs
 */
@Data
@TableName(value = "jsl_sql_statement", autoResultMap = true)
public class SqlStatement {

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
     * 版本ID
     */
    private Long versionId;

    /**
     * Mapper ID (如 com.example.UserMapper.selectById)
     */
    private String statementId;

    /**
     * SQL类型: SELECT/INSERT/UPDATE/DELETE
     */
    private String statementType;

    /**
     * SQL内容的hash，用于快速对比
     */
    private String sqlHash;

    /**
     * 原始SQL文本
     */
    private String rawSql;

    /**
     * 来源类型: XML/ANNOTATION/JDBC
     */
    private String sourceType;

    /**
     * 来源文件路径
     */
    private String sourceFile;

    /**
     * 源代码行号
     */
    private Integer sourceLine;

    /**
     * Mapper类全限定名
     */
    private String mapperClass;

    /**
     * Mapper方法名
     */
    private String mapperMethod;

    /**
     * SqlNode树结构（JSON）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> sqlNodeStructure;

    /**
     * 参数信息（JSON）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> parameterInfo;

    /**
     * 其他元数据（JSON）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    /**
     * 是否动态SQL
     */
    private Boolean isDynamic;

    /**
     * 参数数量
     */
    private Integer paramCount;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
