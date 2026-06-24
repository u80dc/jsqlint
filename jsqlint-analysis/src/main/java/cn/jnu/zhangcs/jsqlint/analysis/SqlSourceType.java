package cn.jnu.zhangcs.jsqlint.analysis;

/**
 * SQL 来源类型枚举
 * <p>
 * 用于标识 SQL 语句的定义方式
 * </p>
 *
 * @author zhangcs
 * @since 1.0.0
 */
public enum SqlSourceType {

    /**
     * 静态 SQL 注解
     * <p>
     * 使用 @Select, @Insert, @Update, @Delete 等注解直接定义 SQL
     * </p>
     */
    STATIC_SQL,

    /**
     * Provider 注解
     * <p>
     * 使用 @SelectProvider, @InsertProvider, @UpdateProvider, @DeleteProvider 等注解，
     * 通过 Provider 类的指定方法动态生成 SQL
     * </p>
     */
    PROVIDER_SQL,

    /**
     * XML 定义的 SQL
     * <p>
     * 通过 MyBatis XML Mapper 文件定义的 SQL 语句
     * </p>
     */
    XML_SQL,

    /**
     * 参数传入的 SQL
     * <p>
     * SQL 作为方法参数传入，如 JdbcTemplate.query(sql, args)
     * </p>
     */
    PARAM_SQL,

    /**
     * SQL 资源文件
     * <p>
     * 通过 schema.sql、data.sql、db/migration/*.sql 等资源文件定义的 SQL
     * </p>
     */
    RESOURCE_SQL,

    /**
     * 未知来源
     */
    UNKNOWN
}
