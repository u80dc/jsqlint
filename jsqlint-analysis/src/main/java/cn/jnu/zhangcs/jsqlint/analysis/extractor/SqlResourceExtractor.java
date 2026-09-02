package cn.jnu.zhangcs.jsqlint.analysis.extractor;

import cn.jnu.zhangcs.jsqlint.analysis.SourceLocation;
import cn.jnu.zhangcs.jsqlint.analysis.SqlSourceType;
import cn.jnu.zhangcs.jsqlint.analysis.StatementType;
import cn.jnu.zhangcs.jsqlint.analysis.node.SqlNode;
import cn.jnu.zhangcs.jsqlint.analysis.node.SqlNodeBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * 资源文件 SQL 提取器
 * <p>
 * 从 {@code schema.sql}、{@code data.sql}、{@code db/migration/*.sql} 等资源文件中提取 SQL 语句。
 * 支持自定义分隔符（{@code DELIMITER} 指令）、行注释（{@code --}、{@code #}）、块注释
 * （{@code /* *}）、字符串字面量（含转义）及 BOM 头处理。
 * </p>
 *
 * @author zhangcs
 */
public final class SqlResourceExtractor implements SqlExtractor {

    /**
     * 默认语句分隔符
     */
    private static final String DEFAULT_DELIMITER = ";";

    @Override
    public String name() {
        return "resource-sql";
    }

    @Override
    public SqlExtractionResult extract(SqlExtractorContext context) {
        return extract(context.source());
    }

    /**
     * 将 SQL 文件内容按分隔符切分为语句列表。
     * <p>
     * 逐字符扫描，识别注释、字符串字面量与自定义分隔符（{@code DELIMITER} 指令），
     * 维护当前语句起始行号，忽略空白语句。
     * </p>
     *
     * @param content 文件内容
     * @return 切分后的语句列表
     */
    static List<ResourceSqlStatement> splitStatements(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        String sql = stripBom(content);
        List<ResourceSqlStatement> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String delimiter = DEFAULT_DELIMITER;
        int line = 1;
        int statementStartLine = 1;
        boolean hasContent = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        char quote = 0;

        for (int i = 0; i < sql.length(); i++) {
            char currentChar = sql.charAt(i);
            char nextChar = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (quote == 0 && !inLineComment && !inBlockComment && isDelimiterDirectiveAtLineStart(sql, i)) {
                int lineEnd = findLineEnd(sql, i);
                delimiter = parseDelimiterDirective(sql.substring(i, lineEnd));
                i = lineEnd - 1;
                continue;
            }

            if (inLineComment) {
                if (currentChar == '\n') {
                    inLineComment = false;
                    line++;
                }
                continue;
            }
            if (inBlockComment) {
                if (currentChar == '*' && nextChar == '/') {
                    inBlockComment = false;
                    i++;
                } else if (currentChar == '\n') {
                    line++;
                }
                continue;
            }

            if (quote == 0) {
                if (currentChar == '-' && nextChar == '-') {
                    inLineComment = true;
                    i++;
                    continue;
                }
                if (currentChar == '#') {
                    inLineComment = true;
                    continue;
                }
                if (currentChar == '/' && nextChar == '*') {
                    inBlockComment = true;
                    i++;
                    continue;
                }
                if (matchesDelimiter(sql, i, delimiter)) {
                    addStatement(statements, current, statementStartLine);
                    current.setLength(0);
                    hasContent = false;
                    i += delimiter.length() - 1;
                    continue;
                }
                if (currentChar == '\'' || currentChar == '"' || currentChar == '`') {
                    quote = currentChar;
                }
            } else if (currentChar == quote) {
                current.append(currentChar);
                if (nextChar == quote && quote != '`') {
                    current.append(nextChar);
                    i++;
                    continue;
                }
                quote = 0;
                continue;
            } else if (currentChar == '\\' && (quote == '\'' || quote == '"') && i + 1 < sql.length()) {
                current.append(currentChar);
                current.append(nextChar);
                i++;
                continue;
            }

            if (!hasContent && !Character.isWhitespace(currentChar)) {
                statementStartLine = line;
                hasContent = true;
            }
            current.append(currentChar);
            if (currentChar == '\n') {
                line++;
            }
        }

        addStatement(statements, current, statementStartLine);
        return List.copyOf(statements);
    }

    /**
     * 将当前累积的内容作为一条语句加入列表（忽略空白）。
     *
     * @param statements 语句列表
     * @param current    当前累积内容
     * @param lineNumber 语句起始行号
     */
    private static void addStatement(List<ResourceSqlStatement> statements, StringBuilder current, int lineNumber) {
        String statement = current.toString().trim();
        if (!statement.isBlank()) {
            statements.add(new ResourceSqlStatement(statement, lineNumber));
        }
    }

    /**
     * 判断指定位置是否位于行首的 {@code DELIMITER} 指令处。
     *
     * @param sql   SQL 文本
     * @param index 当前下标
     * @return 是返回 {@code true}
     */
    private static boolean isDelimiterDirectiveAtLineStart(String sql, int index) {
        int cursor = index;
        while (cursor > 0) {
            char previous = sql.charAt(cursor - 1);
            if (previous == '\n' || previous == '\r') {
                break;
            }
            if (!Character.isWhitespace(previous)) {
                return false;
            }
            cursor--;
        }

        return sql.regionMatches(true, index, "DELIMITER", 0, "DELIMITER".length())
                && index + "DELIMITER".length() < sql.length()
                && Character.isWhitespace(sql.charAt(index + "DELIMITER".length()));
    }

    /**
     * 从指定下标向后查找下一个换行符位置，未找到则取文本末尾。
     *
     * @param sql   SQL 文本
     * @param index 起始下标
     * @return 行结束下标
     */
    private static int findLineEnd(String sql, int index) {
        int lineEnd = sql.indexOf('\n', index);
        return lineEnd < 0 ? sql.length() : lineEnd;
    }

    /**
     * 解析 {@code DELIMITER} 指令行，提取自定义分隔符，缺省回退到默认分隔符。
     *
     * @param line 指令行文本
     * @return 分隔符
     */
    private static String parseDelimiterDirective(String line) {
        String delimiter = line.substring("DELIMITER".length()).trim();
        return delimiter.isEmpty() ? DEFAULT_DELIMITER : delimiter;
    }

    /**
     * 判断 SQL 文本在指定位置是否匹配给定分隔符。
     *
     * @param sql       SQL 文本
     * @param index     当前下标
     * @param delimiter 分隔符
     * @return 匹配返回 {@code true}
     */
    private static boolean matchesDelimiter(String sql, int index, String delimiter) {
        return delimiter != null
                && !delimiter.isEmpty()
                && index + delimiter.length() <= sql.length()
                && sql.startsWith(delimiter, index);
    }

    /**
     * 去除文本开头的 BOM 标记（U+FEFF）。
     *
     * @param content 原始内容
     * @return 去除 BOM 后的内容
     */
    private static String stripBom(String content) {
        return content.startsWith("\uFEFF") ? content.substring(1) : content;
    }

    /**
     * 扫描目录下的 {@code .sql} 文件并提取其中的 SQL 语句。
     *
     * @param source 源目录
     * @return 提取结果；非目录返回空结果
     */
    SqlExtractionResult extract(Path source) {
        if (source == null || !Files.isDirectory(source)) {
            return SqlExtractionResult.empty();
        }

        List<SqlStatement> statements = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        for (Path sqlFile : sqlFiles(source, diagnostics)) {
            extractSqlFile(source, sqlFile, statements, diagnostics);
        }
        return new SqlExtractionResult(statements, diagnostics);
    }

    /**
     * 递归扫描目录下的所有 {@code .sql} 文件，排除构建产物与版本控制目录。
     *
     * @param source      源目录
     * @param diagnostics 用于收集扫描过程中的诊断信息
     * @return SQL 文件列表（已排序）
     */
    private List<Path> sqlFiles(Path source, List<String> diagnostics) {
        try (Stream<Path> stream = Files.walk(source)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql"))
                    .filter(path -> !isIgnoredPath(source.relativize(path)))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            diagnostics.add("Failed to scan SQL resources under " + source + ": " + exception.getMessage());
            return List.of();
        }
    }

    /**
     * 判断相对路径是否应被忽略（位于 target/build/out/.git/node_modules 等目录下）。
     *
     * @param relativePath 相对路径
     * @return 应忽略返回 {@code true}
     */
    private boolean isIgnoredPath(Path relativePath) {
        for (Path part : relativePath) {
            String name = part.toString();
            if ("target".equals(name)
                    || "build".equals(name)
                    || "out".equals(name)
                    || ".git".equals(name)
                    || "node_modules".equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读取单个 SQL 资源文件内容，按分隔符切分语句并逐条构造 {@link SqlStatement}。
     *
     * @param source      源目录
     * @param sqlFile     SQL 文件路径
     * @param statements  用于收集提取结果
     * @param diagnostics 用于收集读取过程中的诊断信息
     */
    private void extractSqlFile(
            Path source,
            Path sqlFile,
            List<SqlStatement> statements,
            List<String> diagnostics) {

        String relativePath = normalizePath(source.relativize(sqlFile));
        String content;
        try {
            content = Files.readString(sqlFile, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            diagnostics.add("Failed to read SQL resource " + relativePath + ": " + exception.getMessage());
            return;
        }

        List<ResourceSqlStatement> resourceStatements = splitStatements(content);
        for (int i = 0; i < resourceStatements.size(); i++) {
            ResourceSqlStatement resourceStatement = resourceStatements.get(i);
            SqlStatement statement = buildResourceStatement(
                    relativePath,
                    i + 1,
                    resourceStatement);
            if (statement != null) {
                statements.add(statement);
            }
        }
    }

    /**
     * 将单条资源 SQL 构造为 {@link SqlStatement}。
     * <p>
     * 识别语句类型并构建 {@link SqlNode} 树，将资源路径与语句下标写入元数据扩展属性。
     * 无法识别或构建失败时返回 {@code null}。
     * </p>
     *
     * @param relativePath      资源相对路径
     * @param statementIndex    语句在文件中的序号
     * @param resourceStatement 资源 SQL 语句
     * @return SQL 语句；无法识别返回 {@code null}
     */
    private SqlStatement buildResourceStatement(
            String relativePath,
            int statementIndex,
            ResourceSqlStatement resourceStatement) {

        String rawSql = resourceStatement.sql().trim();
        StatementType statementType = SqlExtractionSupport.detectStatementType(rawSql);
        if (statementType == null) {
            return null;
        }

        SqlNode sqlNode = SqlNodeBuilder.build(rawSql);
        if (sqlNode == null) {
            return null;
        }

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("resourcePath", relativePath);
        extra.put("statementIndex", statementIndex);

        return new SqlStatement(
                "resource:" + relativePath + "#" + statementIndex,
                statementType,
                SqlSourceType.RESOURCE_SQL,
                SourceLocation.of(
                        "<resource>",
                        relativePath,
                        "sql",
                        List.of(),
                        relativePath,
                        resourceStatement.lineNumber()),
                rawSql,
                sqlNode,
                List.of(),
                List.of(),
                SqlExtractionSupport.metadata(
                        List.of(),
                        List.of(name(), "sql-file"),
                        extra));
    }

    /**
     * 将路径中的反斜杠统一为正斜杠，保证跨平台一致性。
     *
     * @param path 路径
     * @return 规范化后的路径字符串
     */
    private String normalizePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    record ResourceSqlStatement(String sql, int lineNumber) {
    }
}
