package cn.jnu.zhangcs.jsqlint.audit.model;

/**
 * SQL 方言。
 *
 * <p>规则携带方言标识：{@code COMMON}（null，全方言生效）或特定方言（仅同方言 SQL 生效）。
 * SQL 审核时事实对象携带来源项目声明的方言。</p>
 *
 * @author zhangcs
 */
public enum SqlDialect {

    /** MySQL 方言 */
    MYSQL("mysql"),

    /** PostgreSQL 方言 */
    POSTGRESQL("postgresql"),

    /** Oracle 方言 */
    ORACLE("oracle");

    private final String code;

    SqlDialect(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * 按存储 code 解析方言；null/空白/未知值返回 null（视为通用）。
     */
    public static SqlDialect fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (SqlDialect dialect : values()) {
            if (dialect.code.equalsIgnoreCase(code.trim())) {
                return dialect;
            }
        }
        return null;
    }
}
