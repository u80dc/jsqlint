package org.u80dc.jsqlint.commons.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * SQL 词法模板指纹工具。屏蔽字符串、数值和布尔字面量，保留标识符与运算符。
 * 忽略 token 之间的空白、关键字大小写、普通注释及优化器提示。
 * MySQL 可执行注释的 SQL 内容参与归一化；去掉版本前缀，不判断服务器版本是否满足条件。
 * 值位置的 NULL 同样屏蔽，IS [NOT] NULL 等判断结构保留。
 * <p>
 * 默认采用 ANSI 引号规则：单引号表示字符串，双引号表示标识符。
 * 指纹相同仅表示归一化模板相同，不代表 SQL 语义等价；原始 SQL、参数及传播链应另行保留。
 * 本工具不进行完整语法校验，未闭合的引号或块注释会抛出 {@link IllegalArgumentException}。
 *
 * @author u80dc
 */
public final class FingerprintUtils {
    private FingerprintUtils() {
    }

    /**
     * 支持的词法规则；PostgreSQL 普通字符串按 standard_conforming_strings=on 处理。
     * Oracle 支持 Q/NQ 替代引号、浮点后缀以及命名和位置绑定变量。
     */
    public enum Dialect {
        ANSI, POSTGRESQL, MYSQL, ORACLE
    }

    /**
     * 不可变归一化配置。MySQL 开关仅在 MYSQL 方言下生效。
     *
     * @param dialect SQL 方言，不能为 null
     * @param collapseInLists 是否将仅含常量或 JDBC ? 的平面 IN 列表折叠为 IN (?)
     * @param mysqlAnsiQuotes MySQL ANSI_QUOTES：双引号是否表示标识符
     * @param mysqlBackslashEscapes MySQL 是否启用字符串反斜杠转义（NO_BACKSLASH_ESCAPES 关闭时为 true）
     */
    public record Options(Dialect dialect, boolean collapseInLists,
                          boolean mysqlAnsiQuotes, boolean mysqlBackslashEscapes) {
        public Options {
            Objects.requireNonNull(dialect, "dialect");
        }

        public Options(Dialect dialect, boolean collapseInLists) {
            this(dialect, collapseInLists, false, true);
        }

        public static Options defaults() {
            return new Options(Dialect.ANSI, true);
        }

        public Options withCollapseInLists(boolean enabled) {
            return new Options(dialect, enabled, mysqlAnsiQuotes, mysqlBackslashEscapes);
        }

        public Options withMysqlAnsiQuotes(boolean enabled) {
            return new Options(dialect, collapseInLists, enabled, mysqlBackslashEscapes);
        }

        public Options withMysqlBackslashEscapes(boolean enabled) {
            return new Options(dialect, collapseInLists, mysqlAnsiQuotes, enabled);
        }
    }

    /** 按默认 ANSI 规则归一化；null、空白或仅含被忽略注释的输入返回空字符串。 */
    public static String normalize(String sql) {
        return normalize(sql, Options.defaults());
    }

    public static String normalize(String sql, Dialect dialect) {
        return normalize(sql, new Options(dialect, true));
    }

    public static String normalize(String sql, Options options) {
        Objects.requireNonNull(options, "options");
        if (sql == null || sql.isBlank()) {
            return "";
        }
        return new SqlFingerprintNormalizer(sql, options).normalize();
    }

    /** 对归一化模板计算 UTF-8 SHA-256，返回 64 位小写十六进制文本；空模板返回空字符串。 */
    public static String fingerprint(String sql) {
        return fingerprint(sql, Options.defaults());
    }

    public static String fingerprint(String sql, Dialect dialect) {
        return fingerprint(sql, new Options(dialect, true));
    }

    public static String fingerprint(String sql, Options options) {
        String normalized = normalize(sql, options);
        if (normalized.isEmpty()) {
            return "";
        }
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            // Java 规范要求运行环境提供 SHA-256。
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /** 比较词法模板；沿用空输入约定，null 与空字符串视为同一空模板。 */
    public static boolean isSameStructure(String sql1, String sql2) {
        return isSameStructure(sql1, sql2, Options.defaults());
    }

    public static boolean isSameStructure(String sql1, String sql2, Dialect dialect) {
        return isSameStructure(sql1, sql2, new Options(dialect, true));
    }

    public static boolean isSameStructure(String sql1, String sql2, Options options) {
        return normalize(sql1, options).equals(normalize(sql2, options));
    }
}
