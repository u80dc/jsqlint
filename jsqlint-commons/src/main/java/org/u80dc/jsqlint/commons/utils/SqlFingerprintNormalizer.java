package org.u80dc.jsqlint.commons.utils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.u80dc.jsqlint.commons.utils.FingerprintUtils.Dialect.MYSQL;
import static org.u80dc.jsqlint.commons.utils.FingerprintUtils.Dialect.ORACLE;
import static org.u80dc.jsqlint.commons.utils.FingerprintUtils.Dialect.POSTGRESQL;

/**
 * 每次调用独立创建的词法扫描器，避免正则递归以及共享可变状态。
 * 字符串、引号标识符和注释一次完整读取，内部内容不再参与其他替换。
 *
 * @author u80dc
 */
final class SqlFingerprintNormalizer {
    // MySQL 表名大小写依赖服务器配置，扫描时只折叠明确的保留字。
    // VALUE、OFFSET 等非保留关键字先保留，完成扫描后再根据语法位置处理。
    private static final Set<String> MYSQL_RESERVED_WORDS = Set.of(
            "select", "from", "where", "insert", "into", "update", "delete", "using",
            "values", "set", "as", "join", "inner", "outer", "left", "right",
            "cross", "natural", "on", "and", "or", "xor", "not", "in", "is", "null", "like",
            "regexp", "rlike", "between", "exists", "all", "distinct", "union",
            "group", "by", "order", "having", "limit", "asc", "desc",
            "case", "when", "then", "else", "with", "recursive", "over", "partition",
            "range", "for", "create", "alter", "drop", "table", "index", "if", "primary",
            "key", "foreign", "references", "constraint", "unique", "check", "default",
            "cascade", "restrict", "replace", "convert", "collate", "interval", "unsigned",
            "distinctrow", "high_priority", "straight_join", "sql_small_result", "sql_big_result",
            "sql_calc_found_rows", "div", "mod", "both", "leading", "trailing", "match",
            "intersect", "except", "window", "rows", "row", "current_date", "current_time",
            "current_timestamp", "bigint", "binary", "blob", "char", "character", "dec", "decimal",
            "double", "float", "int", "integer", "long", "longblob", "longtext", "mediumblob",
            "mediumint", "mediumtext", "numeric", "real", "smallint", "tinyblob", "tinyint",
            "tinytext", "varbinary", "varchar", "varcharacter", "precision");
    private static final Set<String> MYSQL_STATEMENT_WORDS = Set.of("select", "insert", "replace", "update", "delete");
    private static final Set<String> CASE_VALUE_PREFIXES = Set.of("then", "else", "when", "select", ",", "(");
    private static final Set<String> MYSQL_BUILTIN_FUNCTIONS = Set.of(
            "count", "sum", "avg", "min", "max", "concat", "concat_ws", "coalesce", "ifnull", "nullif",
            "length", "char_length", "lower", "upper", "abs", "round", "floor", "ceil", "ceiling",
            "substring", "substr", "trim", "ltrim", "rtrim", "cast", "date_format", "str_to_date",
            "date_add", "date_sub", "now", "curdate", "curtime", "json_extract", "json_unquote",
            "json_length", "group_concat", "extract");
    private static final Set<String> MYSQL_OBJECT_PREFIXES = Set.of(
            "from", "join", "into", "update", "table", "view", "index", "using", "references",
            "insert", "replace", "with");
    private static final Set<String> MYSQL_INTERVAL_UNITS = Set.of(
            "year", "quarter", "month", "week", "day", "hour", "minute", "second", "microsecond",
            "year_month", "day_hour", "day_minute", "day_second", "day_microsecond", "hour_minute",
            "hour_second", "hour_microsecond", "minute_second", "minute_microsecond", "second_microsecond");
    // 只在明确期待表达式的位置移除数字的一元符号；点号后的关键字仍可能是列名。
    private static final Set<String> OPERAND_PREFIXES = Set.of(
            "select", "where", "having", "on", "and", "or", "xor", "not", "when", "then",
            "else", "by", "values", "set", "returning", "limit", "between", "in", "is",
            "like", "ilike", "regexp", "rlike", "escape", "distinct", "all", "case", "interval");
    // LIMIT、OFFSET、RETURNING 等在 Oracle 中也可作为非保留标识符，不能据此吞掉二元符号。
    private static final Set<String> ORACLE_OPERAND_PREFIXES = Set.of(
            "select", "where", "having", "on", "and", "or", "not", "when", "then", "else",
            "by", "values", "set", "between", "in", "is", "like", "distinct", "all", "case", "prior");
    private static final Set<String> ORACLE_FLOAT_LITERALS = Set.of(
            "binary_float_nan", "binary_double_nan", "binary_float_infinity", "binary_double_infinity");
    private static final List<String> ORACLE_OPERATORS = List.of("=>", "**", "^=");
    private static final List<String> OPERATORS = List.of(
            "->>", "<=>", "::", ":=", "->", "<=", ">=", "<>", "!=", "||", "&&", "<<", ">>");

    private enum Kind { WORD, IDENTIFIER, NUMBER, LITERAL, BIND, OPERATOR, PUNCTUATION }

    private record Token(Kind kind, String text) {
        boolean is(String value) {
            return text.equals(value);
        }

        boolean isListValue() {
            return kind == Kind.NUMBER || kind == Kind.LITERAL;
        }
    }

    private final String sql;
    private final FingerprintUtils.Options options;
    private final List<Token> tokens = new ArrayList<>();
    private int position;

    SqlFingerprintNormalizer(String sql, FingerprintUtils.Options options) {
        this.sql = sql;
        this.options = options;
    }

    String normalize() {
        scan();
        if (options.dialect() == MYSQL) {
            normalizeMysqlContextualWords();
        }
        List<Token> normalized = removeUnaryNumberSigns();
        if (options.collapseInLists()) {
            normalized = collapseInLists(normalized);
        }
        // 忽略语句末尾的终止符，仍保留多语句之间的分隔符。
        while (!normalized.isEmpty() && normalized.getLast().is(";")) {
            normalized.removeLast();
        }
        return format(normalized);
    }

    private void scan() {
        var executableComments = new ArrayDeque<Integer>();
        while (position < sql.length()) {
            char c = sql.charAt(position);
            if (isWhitespace(c)) {
                position++;
            } else if (options.dialect() == MYSQL && starts("/*!")) {
                // 与外部 SQL 共用 token 流，保留跨注释边界的运算符、限定名称和子句上下文。
                executableComments.addLast(position);
                position += 3;
                skipMysqlVersionPrefix();
            } else if (!executableComments.isEmpty() && starts("*/")) {
                executableComments.removeLast();
                position += 2;
            } else if (starts("/*")) {
                blockComment();
            } else if (isLineComment() || (options.dialect() == MYSQL && c == '#')) {
                lineComment();
            } else if (options.dialect() == POSTGRESQL && c == '$' && dollarToken()) {
                // dollarToken 已消费 PostgreSQL 字符串或位置参数。
            } else if (c == '\'' || c == '"' || (options.dialect() == MYSQL && c == '`')) {
                boolean identifier = c == '`' || (c == '"'
                        && (options.dialect() != MYSQL || options.mysqlAnsiQuotes()));
                quoted(position, position, identifier ? Kind.IDENTIFIER : Kind.LITERAL,
                        !identifier && options.dialect() == MYSQL && options.mysqlBackslashEscapes());
            } else if (prefixedQuote()) {
                // 前缀参与词法规则，例如 PostgreSQL E'...' 启用反斜杠转义。
            } else if (options.dialect() == ORACLE && starts("..")) {
                // PL/SQL 范围 1..10 的点号不是两个数值的小数点。
                position += 2;
                add(Kind.OPERATOR, "..");
            } else if (isDigit(c) || (c == '.' && isDigit(at(position + 1)))) {
                number();
            } else if (isWordStart(codePointAt(position))) {
                word();
            } else if (c == ':' && bindToken()) {
                // 绑定名称/编号保持完整，不把 Oracle 的 :1 错当成数值常量。
            } else if (c == '?' && options.dialect() != POSTGRESQL) {
                position++;
                add(Kind.LITERAL, "?");
            } else if (",().;[]".indexOf(c) >= 0) {
                position++;
                add(Kind.PUNCTUATION, String.valueOf(c));
            } else {
                operator();
            }
        }
        if (!executableComments.isEmpty()) {
            throw unterminated("MySQL executable comment", executableComments.getLast());
        }
    }

    /** 仅识别紧随 /*! 的五/六位版本标记；空白之后的数字属于 SQL 字面值。 */
    private void skipMysqlVersionPrefix() {
        int end = position;
        while (isDigit(at(end))) {
            end++;
        }
        int digits = end - position;
        if (digits == 5 || digits == 6) {
            // 未配置目标服务器版本，保留所有可能执行的 SQL 片段，不将版本号当作 SQL 值。
            position = end;
        }
    }

    private boolean prefixedQuote() {
        char c = Character.toLowerCase(at(position));
        boolean postgres = options.dialect() == POSTGRESQL;
        if (options.dialect() == ORACLE) {
            if (c == 'q' && at(position + 1) == '\'') {
                oracleAlternativeQuote(position + 1);
                return true;
            }
            if (c == 'n' && Character.toLowerCase(at(position + 1)) == 'q' && at(position + 2) == '\'') {
                oracleAlternativeQuote(position + 2);
                return true;
            }
        }
        if ((c == 'n' || c == 'b' || c == 'x' || (postgres && c == 'e'))
                && at(position + 1) == '\'') {
            boolean escapes = (postgres && c == 'e')
                    || (options.dialect() == MYSQL && options.mysqlBackslashEscapes());
            quoted(position, position + 1, Kind.LITERAL, escapes);
            return true;
        }
        if (postgres && c == 'u' && at(position + 1) == '&'
                && (at(position + 2) == '\'' || at(position + 2) == '"')) {
            quoted(position, position + 2, at(position + 2) == '"' ? Kind.IDENTIFIER : Kind.LITERAL, false);
            return true;
        }
        return false;
    }

    private void quoted(int start, int quotePosition, Kind kind, boolean backslashEscapes) {
        boolean identifier = kind != Kind.LITERAL;
        char delimiter = sql.charAt(quotePosition);
        position = quotePosition + 1;
        while (position < sql.length()) {
            char c = sql.charAt(position++);
            if (backslashEscapes && c == '\\') {
                if (position < sql.length()) {
                    position++;
                }
            } else if (c == delimiter) {
                if (at(position) == delimiter) {
                    position++;
                } else {
                    // 引号内的大小写、空格、转义形式全部保留，不对标识符做语义重写。
                    String text = identifier ? sql.substring(start, position) : "?";
                    if (identifier && start != quotePosition) {
                        text = sql.substring(start, quotePosition).toLowerCase(Locale.ROOT)
                                + sql.substring(quotePosition, position);
                    }
                    add(kind, text);
                    return;
                }
            }
        }
        throw unterminated(identifier ? "quoted identifier" : "string literal", start);
    }

    /** Oracle Q/NQ 引号仅由“结束分隔符 + 单引号”终止，内部单引号无需转义。 */
    private void oracleAlternativeQuote(int quotePosition) {
        int start = position;
        int opening = codePointAt(quotePosition + 1);
        if (opening < 0) {
            throw unterminated("Oracle alternative-quoted string", start);
        }
        if (Character.isWhitespace(opening) || Character.isSpaceChar(opening) || opening == 0) {
            throw new IllegalArgumentException("Invalid Oracle quote delimiter at position " + (quotePosition + 1));
        }
        int closing = switch (opening) {
            case '[' -> ']';
            case '{' -> '}';
            case '(' -> ')';
            case '<' -> '>';
            default -> opening;
        };
        position = quotePosition + 1 + Character.charCount(opening);
        while (position < sql.length()) {
            int c = codePointAt(position);
            int next = position + Character.charCount(c);
            if (c == closing && at(next) == '\'') {
                position = next + 1;
                add(Kind.LITERAL, "?");
                return;
            }
            position = next;
        }
        throw unterminated("Oracle alternative-quoted string", start);
    }

    private boolean bindToken() {
        if (starts("::") || starts(":=")) {
            return false;
        }
        if (options.dialect() == ORACLE && at(position + 1) == '"') {
            quoted(position, position + 1, Kind.BIND, false);
            return true;
        }
        if (isWordStart(codePointAt(position + 1)) || (options.dialect() == ORACLE && isDigit(at(position + 1)))) {
            int start = position++;
            consumeWord();
            add(Kind.BIND, sql.substring(start, position));
            return true;
        }
        return false;
    }

    private boolean dollarToken() {
        int start = position;
        int end = start + 1;
        if (isDigit(at(end))) {
            while (isDigit(at(end))) {
                end++;
            }
            add(Kind.BIND, sql.substring(start, end));
            position = end;
            return true;
        }
        if (isWordStart(codePointAt(end)) && at(end) != '$') {
            while (isWordPart(codePointAt(end)) && at(end) != '$') {
                end += Character.charCount(codePointAt(end));
            }
        }
        if (at(end) != '$') {
            return false;
        }
        String delimiter = sql.substring(start, end + 1);
        int close = sql.indexOf(delimiter, end + 1);
        if (close < 0) {
            throw unterminated("dollar-quoted string", start);
        }
        position = close + delimiter.length();
        add(Kind.LITERAL, "?");
        return true;
    }

    private void lineComment() {
        while (position < sql.length() && at(position) != '\n' && at(position) != '\r') {
            position++;
        }
    }

    private boolean isLineComment() {
        return starts("--") && (options.dialect() != MYSQL || position + 2 == sql.length()
                || isWhitespace(at(position + 2)) || Character.isISOControl(at(position + 2)));
    }

    private void blockComment() {
        int start = position;
        // 普通块注释和优化器提示被忽略；MySQL 可执行注释在 scan 中展开。
        position += 2;
        int depth = 1;
        while (position < sql.length()) {
            if (options.dialect() == POSTGRESQL && starts("/*")) {
                depth++;
                position += 2;
            } else if (starts("*/")) {
                position += 2;
                if (--depth == 0) {
                    return;
                }
            } else {
                position++;
            }
        }
        throw unterminated("block comment", start);
    }

    private void number() {
        int start = position;
        if (at(position) == '0' && (at(position + 1) == 'x' || at(position + 1) == 'X')
                && isHex(at(position + 2))) {
            position += 2;
            while (isHex(at(position))) {
                position++;
            }
        } else {
            while (isDigit(at(position))) {
                position++;
            }
            if (at(position) == '.' && !(options.dialect() == ORACLE && starts(".."))) {
                position++;
                while (isDigit(at(position))) {
                    position++;
                }
            }
            if (at(position) == 'e' || at(position) == 'E') {
                int exponent = position + 1;
                if (at(exponent) == '+' || at(exponent) == '-') {
                    exponent++;
                }
                if (isDigit(at(exponent))) {
                    position = exponent + 1;
                    while (isDigit(at(position))) {
                        position++;
                    }
                }
            }
        }
        // Oracle 的 f/d 后缀属于整个数值 token；标识符中的 f/d 则保留。
        if (options.dialect() == ORACLE && (Character.toLowerCase(at(position)) == 'f'
                || Character.toLowerCase(at(position)) == 'd') && !isWordPart(codePointAt(position + 1))) {
            position++;
        }
        // MySQL 允许部分以数字起始的标识符，不能将它们拆成数值和列名。
        if (isWordPart(codePointAt(position))) {
            consumeWord();
            add(Kind.WORD, foldWord(sql.substring(start, position)));
        } else {
            add(Kind.NUMBER, "?");
        }
    }

    private void word() {
        int start = position;
        consumeWord();
        String word = sql.substring(start, position);
        if (!afterDot() && (word.equalsIgnoreCase("true") || word.equalsIgnoreCase("false"))) {
            add(Kind.LITERAL, "?");
        } else if (!afterDot() && options.dialect() == ORACLE && ORACLE_FLOAT_LITERALS.contains(word.toLowerCase(Locale.ROOT))) {
            add(Kind.NUMBER, "?");
        } else {
            add(Kind.WORD, foldWord(word));
        }
    }

    private void consumeWord() {
        while (isWordPart(codePointAt(position))) {
            position += Character.charCount(codePointAt(position));
        }
    }

    private String foldWord(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        return options.dialect() != MYSQL || (!afterDot() && MYSQL_RESERVED_WORDS.contains(lower)) ? lower : word;
    }

    private boolean afterDot() {
        return !tokens.isEmpty() && tokens.getLast().is(".");
    }

    /** MySQL 的非保留关键字可作标识符，仅在对应语法位置折叠大小写。 */
    private void normalizeMysqlContextualWords() {
        int cases = 0;
        int depth = 0;
        String statement = "";
        var windows = new ArrayDeque<Integer>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            String previous = wordAt(i - 1);
            String next = wordAt(i + 1);
            if (token.is("(")) {
                depth++;
                if (previous.equals("over")) {
                    windows.push(depth);
                }
            } else if (token.is(")")) {
                if (!windows.isEmpty() && windows.peek() == depth) {
                    windows.pop();
                }
                depth--;
            } else if (token.is(";")) {
                cases = 0;
                statement = "";
                windows.clear();
                depth = 0;
            }
            if (token.kind != Kind.WORD || previous.equals(".") || next.equals(".")) {
                continue;
            }
            String lower = token.text.toLowerCase(Locale.ROOT);
            if (depth == 0 && MYSQL_STATEMENT_WORDS.contains(lower)) {
                statement = lower;
            }
            boolean keyword = false;
            if (lower.equals("case")) {
                cases++;
            } else if (lower.equals("end") && cases > 0
                    && !CASE_VALUE_PREFIXES.contains(previous)) {
                cases--;
                keyword = true;
            }
            keyword |= switch (lower) {
                case "offset" -> wordAt(i - 2).equals("limit") && isValueAt(i - 1);
                case "value" -> (statement.equals("insert") || statement.equals("replace"))
                        && next.equals("(") && !MYSQL_OBJECT_PREFIXES.contains(previous);
                case "duplicate" -> previous.equals("on") && next.equals("key") && wordAt(i + 2).equals("update");
                case "escape" -> isValueAt(i + 1);
                case "date", "time", "timestamp" -> isValueAt(i + 1)
                        || (previous.equals("as") && (next.equals(")") || next.equals("(")));
                case "signed", "datetime" -> previous.equals("as") && (next.equals(")") || next.equals("("));
                case "share" -> previous.equals("for");
                case "nowait" -> isMysqlLockAt(i - 1);
                case "skip" -> isMysqlLockAt(i - 1) && next.equals("locked");
                case "locked" -> previous.equals("skip") && isMysqlLockAt(i - 2);
                default -> false;
            };
            if (!windows.isEmpty() && depth == windows.peek()) {
                keyword |= switch (lower) {
                    case "unbounded" -> next.equals("preceding") || next.equals("following");
                    case "preceding", "following" -> previous.equals("unbounded") || isValueAt(i - 1)
                            || MYSQL_INTERVAL_UNITS.contains(previous);
                    case "current" -> next.equals("row");
                    case "row" -> previous.equals("current");
                    case "rows" -> next.equals("between") || next.equals("unbounded") || isValueAt(i + 1);
                    default -> false;
                };
            }
            keyword |= MYSQL_INTERVAL_UNITS.contains(lower) && followsIntervalValue(i);
            boolean function = MYSQL_BUILTIN_FUNCTIONS.contains(lower) && next.equals("(")
                    && !MYSQL_OBJECT_PREFIXES.contains(previous);
            if (keyword || function) {
                tokens.set(i, new Token(Kind.WORD, lower));
            }
        }
    }

    private boolean isMysqlLockAt(int index) {
        return wordAt(index - 1).equals("for")
                && (wordAt(index).equals("update") || wordAt(index).equals("share"));
    }

    private boolean followsIntervalValue(int index) {
        if (!isValueAt(index - 1)) {
            return false;
        }
        int prefix = index - 2;
        while (wordAt(prefix).equals("+") || wordAt(prefix).equals("-")) {
            prefix--;
        }
        return wordAt(prefix).equals("interval");
    }

    private boolean isValueAt(int index) {
        return index >= 0 && index < tokens.size()
                && (tokens.get(index).isListValue() || tokens.get(index).kind == Kind.BIND);
    }

    private String wordAt(int index) {
        if (index < 0 || index >= tokens.size()) {
            return "";
        }
        Token token = tokens.get(index);
        return token.kind == Kind.WORD ? token.text.toLowerCase(Locale.ROOT) : token.text;
    }

    private void operator() {
        int start = position;
        if (options.dialect() == ORACLE) {
            for (String operator : ORACLE_OPERATORS) {
                if (starts(operator)) {
                    position += operator.length();
                    add(Kind.OPERATOR, operator);
                    return;
                }
            }
        }
        if (options.dialect() == POSTGRESQL && isPostgresOperator(at(position))) {
            boolean special = false;
            while (isPostgresOperator(at(position))) {
                if (starts("--") || starts("/*")) {
                    break;
                }
                // JDBC 的 id=? 应拆为比较运算符和占位符；@? 则是 PostgreSQL JSON 运算符。
                if (at(position) == '?' && position > start && at(start) != '?'
                        && !(position == start + 1 && at(start) == '@')) {
                    break;
                }
                special |= "~!@#%^&|`?".indexOf(at(position)) >= 0;
                position++;
            }
            // PostgreSQL 普通运算符尾部的 +/- 是下一数值的一元符号。
            while (!special && position > start + 1 && "+-".indexOf(at(position - 1)) >= 0) {
                position--;
            }
            add(Kind.OPERATOR, sql.substring(start, position));
            return;
        }
        for (String operator : OPERATORS) {
            if (starts(operator)) {
                position += operator.length();
                add(Kind.OPERATOR, operator);
                return;
            }
        }
        position++;
        add(Kind.OPERATOR, sql.substring(start, position));
    }

    private List<Token> removeUnaryNumberSigns() {
        List<Token> result = new ArrayList<>(tokens.size());
        boolean expectsOperand = true;
        Token previous = null;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (expectsOperand && (token.is("+") || token.is("-"))) {
                int end = i + 1;
                while (end < tokens.size() && (tokens.get(end).is("+") || tokens.get(end).is("-"))) {
                    end++;
                }
                if (end < tokens.size() && tokens.get(end).kind == Kind.NUMBER) {
                    i = end;
                    token = tokens.get(end);
                } else {
                    // 一次跳过整个符号串，避免长表达式被反复向后扫描。
                    result.addAll(tokens.subList(i, end));
                    previous = tokens.get(end - 1);
                    i = end - 1;
                    continue;
                }
            }
            // PostgreSQL 单独的 ? 在期待表达式处可用作 JDBC 占位符，否则保留为 JSON 运算符。
            if (token.kind == Kind.OPERATOR && token.is("?") && expectsOperand) {
                token = new Token(Kind.LITERAL, "?");
            }
            // 值位置的 NULL 与其他字面量一样屏蔽；IS [NOT] NULL、NOT NULL 仍保留结构。
            if (token.kind == Kind.WORD && token.is("null") && expectsOperand && previous != null
                    && !previous.is("is") && !previous.is("not")
                    && !(options.dialect() == ORACLE && i + 1 < tokens.size() && tokens.get(i + 1).is(";")
                    && (previous.is("then") || previous.is("else")))) {
                token = new Token(Kind.LITERAL, "?");
            }
            result.add(token);
            expectsOperand = expectsOperandAfter(token, previous);
            previous = token;
        }
        return result;
    }

    private boolean expectsOperandAfter(Token token, Token previous) {
        if (token.kind == Kind.OPERATOR) {
            return true;
        }
        if (token.kind == Kind.PUNCTUATION) {
            return token.is("(") || token.is(",") || token.is(";") || token.is("[");
        }
        if (options.dialect() == ORACLE) {
            return token.kind == Kind.WORD && (previous == null || !previous.is("."))
                    && ORACLE_OPERAND_PREFIXES.contains(token.text);
        }
        return token.kind == Kind.WORD && (previous == null || !previous.is("."))
                && (options.dialect() != MYSQL || MYSQL_RESERVED_WORDS.contains(token.text))
                && (OPERAND_PREFIXES.contains(token.text)
                || (options.dialect() != MYSQL && token.is("offset")));
    }

    private static List<Token> collapseInLists(List<Token> source) {
        List<Token> result = new ArrayList<>(source.size());
        for (int i = 0; i < source.size(); i++) {
            Token token = source.get(i);
            result.add(token);
            if (token.kind != Kind.WORD || !token.is("in") || (i > 0 && source.get(i - 1).is("."))
                    || i + 2 >= source.size()
                    || !source.get(i + 1).is("(") || !source.get(i + 2).isListValue()) {
                continue;
            }
            int end = i + 3;
            while (end + 1 < source.size() && source.get(end).is(",") && source.get(end + 1).isListValue()) {
                end += 2;
            }
            if (end < source.size() && source.get(end).is(")")) {
                result.add(source.get(i + 1));
                result.add(new Token(Kind.LITERAL, "?"));
                result.add(source.get(end));
                i = end;
            }
        }
        return result;
    }

    private static String format(List<Token> tokens) {
        StringBuilder result = new StringBuilder();
        Token previous = null;
        for (Token token : tokens) {
            if (previous != null && !token.is(",") && !token.is(")") && !token.is(".")
                    && !token.is(";") && !token.is("]") && !previous.is("(")
                    && !previous.is(".") && !previous.is("[")) {
                result.append(' ');
            }
            result.append(token.text);
            previous = token;
        }
        return result.toString();
    }

    private void add(Kind kind, String text) {
        tokens.add(new Token(kind, text));
    }

    private boolean starts(String text) {
        return sql.startsWith(text, position);
    }

    private char at(int index) {
        return index < sql.length() ? sql.charAt(index) : '\0';
    }

    private int codePointAt(int index) {
        return index < sql.length() ? sql.codePointAt(index) : -1;
    }

    private static boolean isWordStart(int c) {
        return c == '_' || c == '$' || Character.isLetter(c);
    }

    private boolean isWordPart(int c) {
        return isWordStart(c) || Character.isDigit(c) || Character.getType(c) == Character.NON_SPACING_MARK
                || (options.dialect() == ORACLE && c == '#');
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isWhitespace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    private static boolean isHex(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isPostgresOperator(char c) {
        return c != '\0' && "+-*/<>=~!@#%^&|`?".indexOf(c) >= 0;
    }

    private static IllegalArgumentException unterminated(String token, int position) {
        return new IllegalArgumentException("Unterminated " + token + " at position " + position);
    }
}
