package cn.jnu.zhangcs.jsqlint.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * SQL 指纹特征工具
 * <p>
 * 对 SQL 进行结构化归一化后生成指纹，用于判断两条 SQL 在结构上是否相同。
 * 屏蔽字面值差异（如 id=2 与 id=6 认为是同一条 SQL），
 * 仅当 SQL 的关键字、表名、列名等结构部分不同时才产生不同的指纹。
 *
 * @author zhangcs
 */
public final class FingerprintUtils {

    private FingerprintUtils() {
    }

    // -- 行注释: -- xxx
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\r\\n]*");

    // 块注释: /* xxx */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    // 单引号字符串字面量（含转义单引号 ''）
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");

    // 双引号字符串字面量（MySQL 等）
    private static final Pattern DOUBLE_QUOTED_LITERAL = Pattern.compile("\"(?:[^\"]|\"\")*\"");

    // 十六进制字面量 0xABCD
    private static final Pattern HEX_LITERAL = Pattern.compile("\\b0x[0-9a-fA-F]+\\b");

    // 数值字面量（整数、小数、科学计数法）
    // 匹配独立数字，前后不接字母/下划线（避免匹配标识符中的数字）
    private static final Pattern NUMERIC_LITERAL = Pattern.compile(
            "(?<!\\w)[-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?(?!\\w)");

    // IN 列表归一化: IN (?, ?, ..., ?) → IN (?)
    private static final Pattern IN_LIST = Pattern.compile(
            "\\bin\\s*\\(\\s*\\?(?:\\s*,\\s*\\?)*\\s*\\)", Pattern.CASE_INSENSITIVE);

    // 比较运算符周围空白归一化（确保 id=? 与 id = ? 一致）
    private static final Pattern COMPARISON_OPERATOR = Pattern.compile(
            "\\s*(<>|!=|<=|>=|[=<>])\\s*");

    // 多余空白
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * 归一化 SQL，返回去除字面值后的结构字符串。
     * <p>
     * 归一化步骤：
     * <ol>
     *   <li>去除 SQL 注释（行注释和块注释）</li>
     *   <li>统一转为小写</li>
     *   <li>替换字符串字面量为 ?</li>
     *   <li>替换十六进制字面量为 ?</li>
     *   <li>替换数值字面量为 ?</li>
     *   <li>归一化 IN 列表（IN (?,?,?) → IN (?)）</li>
     *   <li>归一化比较运算符周围空白（id=? → id = ?）</li>
     *   <li>折叠空白并 trim</li>
     * </ol>
     *
     * @param sql 原始 SQL
     * @return 归一化后的 SQL 结构字符串，输入为 null 或空时返回空字符串
     */
    public static String normalize(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }

        String result = sql;

        // 1. 去除注释
        result = BLOCK_COMMENT.matcher(result).replaceAll(" ");
        result = LINE_COMMENT.matcher(result).replaceAll(" ");

        // 2. 统一小写
        result = result.toLowerCase();

        // 3. 替换字符串字面量
        result = STRING_LITERAL.matcher(result).replaceAll("?");
        result = DOUBLE_QUOTED_LITERAL.matcher(result).replaceAll("?");

        // 4. 替换十六进制字面量
        result = HEX_LITERAL.matcher(result).replaceAll("?");

        // 5. 替换数值字面量
        result = NUMERIC_LITERAL.matcher(result).replaceAll("?");

        // 6. 归一化 IN 列表
        result = IN_LIST.matcher(result).replaceAll("in (?)");

        // 7. 归一化比较运算符周围空白（确保 id=? 与 id = ? 一致）
        result = COMPARISON_OPERATOR.matcher(result).replaceAll(" $1 ");

        // 8. 折叠空白
        result = WHITESPACE.matcher(result).replaceAll(" ").trim();

        return result;
    }

    /**
     * 生成 SQL 指纹（SHA-256 十六进制字符串）。
     * <p>
     * 对 SQL 先进行归一化，再计算 SHA-256 哈希。
     *
     * @param sql 原始 SQL
     * @return 64 字符的十六进制指纹字符串，输入为空时返回空字符串
     */
    public static String fingerprint(String sql) {
        String normalized = normalize(sql);
        if (normalized.isEmpty()) {
            return "";
        }
        return sha256(normalized);
    }

    /**
     * 判断两条 SQL 在结构上是否相同。
     * <p>
     * 两条 SQL 归一化后的字符串相同则认为结构相同。
     *
     * @param sql1 第一条 SQL
     * @param sql2 第二条 SQL
     * @return 结构相同返回 true
     */
    public static boolean isSameStructure(String sql1, String sql2) {
        return normalize(sql1).equals(normalize(sql2));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return toHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by Java spec
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}