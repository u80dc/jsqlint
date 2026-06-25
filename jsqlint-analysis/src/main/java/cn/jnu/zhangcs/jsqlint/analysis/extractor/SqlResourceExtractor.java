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
 * Extracts SQL statements from resource files such as schema.sql and data.sql.
 */
public final class SqlResourceExtractor implements SqlExtractor {

    private static final String DEFAULT_DELIMITER = ";";

    @Override
    public String name() {
        return "resource-sql";
    }

    @Override
    public SqlExtractionResult extract(SqlExtractorContext context) {
        return extract(context.source());
    }

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
                        0,
                        List.of(),
                        sqlNode,
                        List.of(name(), "sql-file"),
                        extra));
    }

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

    private static void addStatement(List<ResourceSqlStatement> statements, StringBuilder current, int lineNumber) {
        String statement = current.toString().trim();
        if (!statement.isBlank()) {
            statements.add(new ResourceSqlStatement(statement, lineNumber));
        }
    }

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

    private static int findLineEnd(String sql, int index) {
        int lineEnd = sql.indexOf('\n', index);
        return lineEnd < 0 ? sql.length() : lineEnd;
    }

    private static String parseDelimiterDirective(String line) {
        String delimiter = line.substring("DELIMITER".length()).trim();
        return delimiter.isEmpty() ? DEFAULT_DELIMITER : delimiter;
    }

    private static boolean matchesDelimiter(String sql, int index, String delimiter) {
        return delimiter != null
                && !delimiter.isEmpty()
                && index + delimiter.length() <= sql.length()
                && sql.startsWith(delimiter, index);
    }

    private static String stripBom(String content) {
        return content.startsWith("\uFEFF") ? content.substring(1) : content;
    }

    private String normalizePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    record ResourceSqlStatement(String sql, int lineNumber) {
    }
}
