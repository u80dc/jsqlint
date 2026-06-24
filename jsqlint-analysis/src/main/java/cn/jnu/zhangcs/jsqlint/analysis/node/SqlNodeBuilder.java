package cn.jnu.zhangcs.jsqlint.analysis.node;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 节点解析器
 * <p>
 *     将 MyBatis 动态 SQL 字符串解析为 SqlNode 节点树
 * </p>
 *
 * @author zhangcs
 * @since 1.0.0
 */
public class SqlNodeBuilder {

    /**
     * 脚本标签正则
     */
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("^<script>(.*)</script>$", Pattern.DOTALL);

    /**
     * WHERE 标签正则
     */
    private static final Pattern WHERE_PATTERN = Pattern.compile("<where[^>]*>(.*?)</where>", Pattern.DOTALL);

    /**
     * SET 标签正则
     */
    private static final Pattern SET_PATTERN = Pattern.compile("<set[^>]*>(.*?)</set>", Pattern.DOTALL);

    /**
     * CHOOSE 标签正则
     */
    private static final Pattern CHOOSE_PATTERN = Pattern.compile("<choose[^>]*>(.*?)</choose>", Pattern.DOTALL);

    /**
     * OTHERWISE 标签正则
     */
    private static final Pattern OTHERWISE_PATTERN = Pattern.compile("<otherwise[^>]*>(.*?)</otherwise>", Pattern.DOTALL);

    /**
     * 将动态 SQL 字符串解析为 SqlNode 节点
     *
     * @param script SQL 内容字符串
     * @return SqlNode 节点，如果解析失败返回 null
     */
    public static SqlNode build(String script) {
        if (script == null) {
            return null;
        }

        String content = script.trim();
        if (content.isEmpty()) {
            return null;
        }

        // 从<script>标签中提取脚本内容
        if (content.contains("<script>") && content.contains("</script>")) {
            Matcher scriptMatcher = SCRIPT_PATTERN.matcher(content);
            if (scriptMatcher.matches()) {
                // 正则匹配成功，直接提取
                content = scriptMatcher.group(1);
            } else {
                // 如果正则匹配失败，手动提取
                int start = content.indexOf("<script>") + "<script>".length();
                int end = content.lastIndexOf("</script>");
                if (start < end) {
                    content = content.substring(start, end);
                }
            }
        }
        // 解析内容
        return parseContent(content);
    }

    /**
     * 解析 SQL 内容
     */
    private static SqlNode parseContent(String content) {
        if (content == null) {
            return null;
        }

        String remaining = content.trim();
        if (remaining.isEmpty()) {
            return null;
        }

        List<SqlNode> nodes = new ArrayList<>();
        while (!remaining.isEmpty()) {
            SqlNodeAndRemaining result = extractNextNode(remaining);
            if (result == null) {
                break;
            }
            nodes.add(result.node);
            remaining = result.remaining;
        }

        if (nodes.isEmpty()) {
            return null;
        }

        if (nodes.size() == 1) {
            return nodes.getFirst();
        }

        return new MixedSqlNode(nodes);
    }

    /**
     * 提取下一个节点及其剩余内容
     */
    private static SqlNodeAndRemaining extractNextNode(String content) {
        content = content.trim();
        if (content.isEmpty()) {
            return null;
        }

        // 检查 IF 标签
        if (content.startsWith("<if")) {
            IfTagResult ifResult = parseIfTag(content);
            if (ifResult != null) {
                SqlNode contents = parseContent(ifResult.innerContent);
                IfSqlNode ifNode = new IfSqlNode(ifResult.test, contents != null ? contents : new StaticTextSqlNode(""));
                return new SqlNodeAndRemaining(ifNode, ifResult.remaining);
            }
        }

        // 检查 WHERE 标签
        Matcher whereMatcher = WHERE_PATTERN.matcher(content);
        if (whereMatcher.lookingAt()) {
            String innerContent = whereMatcher.group(1);
            SqlNode contents = parseContent(innerContent);
            WhereSqlNode whereNode = new WhereSqlNode(contents != null ? contents : new StaticTextSqlNode(""));
            return new SqlNodeAndRemaining(whereNode, content.substring(whereMatcher.end()));
        }

        // 检查 SET 标签
        Matcher setMatcher = SET_PATTERN.matcher(content);
        if (setMatcher.lookingAt()) {
            String innerContent = setMatcher.group(1);
            SqlNode contents = parseContent(innerContent);
            SetSqlNode setNode = new SetSqlNode(contents != null ? contents : new StaticTextSqlNode(""));
            return new SqlNodeAndRemaining(setNode, content.substring(setMatcher.end()));
        }

        // 检查 TRIM 标签
        if (content.startsWith("<trim")) {
            TrimTagResult trimResult = parseTrimTag(content);
            if (trimResult != null) {
                SqlNode contents = parseContent(trimResult.innerContent);
                TrimSqlNode trimNode = new TrimSqlNode(trimResult.prefix, trimResult.suffix,
                        trimResult.prefixOverrides, trimResult.suffixOverrides,
                        contents != null ? contents : new StaticTextSqlNode(""));
                return new SqlNodeAndRemaining(trimNode, trimResult.remaining);
            }
        }

        // 检查 FOREACH 标签
        if (content.startsWith("<foreach")) {
            ForEachTagResult foreachResult = parseForEachTag(content);
            if (foreachResult != null) {
                SqlNode contents = parseContent(foreachResult.innerContent);
                ForEachSqlNode foreachNode = new ForEachSqlNode(foreachResult.collection, foreachResult.item,
                        foreachResult.index, foreachResult.open, foreachResult.close, foreachResult.separator,
                        contents != null ? contents : new StaticTextSqlNode(""));
                return new SqlNodeAndRemaining(foreachNode, foreachResult.remaining);
            }
        }

        // 检查 CHOOSE 标签
        Matcher chooseMatcher = CHOOSE_PATTERN.matcher(content);
        if (chooseMatcher.lookingAt()) {
            String innerContent = chooseMatcher.group(1);
            ChooseSqlNode chooseNode = parseChoose(innerContent);
            return new SqlNodeAndRemaining(chooseNode, content.substring(chooseMatcher.end()));
        }

        // 检查 BIND 标签
        if (content.startsWith("<bind")) {
            BindTagResult bindResult = parseBindTag(content);
            if (bindResult != null) {
                BindSqlNode bind = new BindSqlNode(bindResult.name, bindResult.expression);
                return new SqlNodeAndRemaining(bind, bindResult.remaining);
            }
        }

        // 处理静态文本 - 提取到下一个标签之前的内容
        int nextTagIndex = findNextTagIndex(content);
        if (nextTagIndex > 0) {
            String text = content.substring(0, nextTagIndex).trim();
            if (!text.isEmpty()) {
                SqlNode textNode = createTextNode(text);
                return new SqlNodeAndRemaining(textNode, content.substring(nextTagIndex));
            }
        } else if (nextTagIndex < 0) {
            // 没有找到任何标签，作为纯文本处理
            SqlNode textNode = createTextNode(content.trim());
            return new SqlNodeAndRemaining(textNode, "");
        }

        return null;
    }

    /**
     * 解析 IF 标签
     */
    private static IfTagResult parseIfTag(String content) {
        return parseConditionTag(content, "<if", "</if>");
    }

    /**
     * 解析 WHEN 标签
     */
    private static IfTagResult parseWhenTag(String content) {
        return parseConditionTag(content, "<when", "</when>");
    }

    /**
     * 解析条件标签（IF/WHEN）
     */
    private static IfTagResult parseConditionTag(String content, String openTag, String closeTag) {
        int testStart = content.indexOf("test=");
        if (testStart < 0 || testStart > openTag.length() + 5) {
            return null;
        }

        testStart += 5;
        if (testStart >= content.length()) {
            return null;
        }

        char quote = content.charAt(testStart);
        if (quote != '"' && quote != '\'') {
            return null;
        }

        int testEnd = testStart + 1;
        StringBuilder testBuilder = new StringBuilder();
        while (testEnd < content.length()) {
            char c = content.charAt(testEnd);
            if (c == quote) {
                break;
            }
            testBuilder.append(c);
            testEnd++;
        }

        if (testEnd >= content.length()) {
            return null;
        }

        int contentStart = content.indexOf('>', testEnd) + 1;
        if (contentStart <= 0) {
            return null;
        }

        String test = testBuilder.toString();
        int closeIndex = findMatchingCloseTag(content, contentStart, openTag, closeTag);
        if (closeIndex < 0) {
            return null;
        }

        String innerContent = content.substring(contentStart, closeIndex);
        String remaining = content.substring(closeIndex + closeTag.length());
        return new IfTagResult(test, innerContent, remaining);
    }

    /**
     * 解析 TRIM 标签
     */
    private static TrimTagResult parseTrimTag(String content) {
        String prefix = extractAttribute(content, "prefix");
        String suffix = extractAttribute(content, "suffix");
        String prefixOverrides = extractAttribute(content, "prefixOverrides");
        String suffixOverrides = extractAttribute(content, "suffixOverrides");

        if (prefix == null || suffix == null || prefixOverrides == null || suffixOverrides == null) {
            return null;
        }

        int closeIndex = findMatchingCloseTag(content, content.indexOf('>') + 1, "<trim", "</trim>");
        if (closeIndex < 0) {
            return null;
        }

        int contentStart = content.indexOf('>') + 1;
        String innerContent = content.substring(contentStart, closeIndex);
        String remaining = content.substring(closeIndex + "</trim>".length());
        return new TrimTagResult(prefix, suffix, prefixOverrides, suffixOverrides, innerContent, remaining);
    }

    /**
     * 解析 FOREACH 标签
     */
    private static ForEachTagResult parseForEachTag(String content) {
        String collection = extractAttribute(content, "collection");
        String item = extractAttribute(content, "item");
        String index = extractAttribute(content, "index");
        String open = extractAttribute(content, "open");
        String close = extractAttribute(content, "close");
        String separator = extractAttribute(content, "separator");

        if (collection == null || item == null) {
            return null;
        }

        int closeIndex = findMatchingCloseTag(content, content.indexOf('>') + 1, "<foreach", "</foreach>");
        if (closeIndex < 0) {
            return null;
        }

        int contentStart = content.indexOf('>') + 1;
        String innerContent = content.substring(contentStart, closeIndex);
        String remaining = content.substring(closeIndex + "</foreach>".length());
        return new ForEachTagResult(collection, item, index, open, close, separator, innerContent, remaining);
    }

    /**
     * 解析 BIND 标签
     */
    private static BindTagResult parseBindTag(String content) {
        String name = extractAttribute(content, "name");
        String value = extractAttribute(content, "value");

        if (name == null || value == null) {
            return null;
        }

        int closeIndex = content.indexOf("/>", 5);
        if (closeIndex < 0) {
            closeIndex = content.indexOf('>', 5);
        }
        if (closeIndex < 0) {
            return null;
        }

        String remaining = content.substring(closeIndex + 2).trim();
        return new BindTagResult(name, value, remaining);
    }

    /**
     * 提取标签属性值（支持单引号和双引号）
     */
    private static String extractAttribute(String content, String attrName) {
        String pattern = attrName + "=";
        int attrStart = content.indexOf(pattern);
        if (attrStart < 0) {
            return "";
        }

        attrStart += pattern.length();
        if (attrStart >= content.length()) {
            return null;
        }

        char quote = content.charAt(attrStart);
        if (quote != '"' && quote != '\'') {
            return null;
        }

        int valueStart = attrStart + 1;
        int valueEnd = valueStart;

        while (valueEnd < content.length()) {
            char c = content.charAt(valueEnd);
            if (c == quote) {
                break;
            }
            valueEnd++;
        }

        if (valueEnd >= content.length()) {
            return null;
        }

        return content.substring(valueStart, valueEnd);
    }

    /**
     * 解析 CHOOSE 节点
     */
    private static ChooseSqlNode parseChoose(String content) {
        List<SqlNode> whenContents = new ArrayList<>();
        SqlNode otherwiseContents = null;

        // 手动提取 WHEN 标签
        int pos = 0;
        while (pos < content.length()) {
            int whenStart = content.indexOf("<when", pos);
            if (whenStart < 0) {
                break;
            }

            IfTagResult whenResult = parseWhenTag(content.substring(whenStart));
            if (whenResult != null) {
                SqlNode whenNodeContents = parseContent(whenResult.innerContent());
                IfSqlNode whenNode = new IfSqlNode(whenResult.test(),
                        whenNodeContents != null ? whenNodeContents : new StaticTextSqlNode(""));
                whenContents.add(whenNode);
                // 移动到 remaining 的开始位置
                pos = content.length() - whenResult.remaining().length();
            } else {
                pos = whenStart + 1;
            }
        }

        // 提取 OTHERWISE 标签
        Matcher otherwiseMatcher = OTHERWISE_PATTERN.matcher(content);
        if (otherwiseMatcher.find()) {
            String innerContent = otherwiseMatcher.group(1);
            otherwiseContents = parseContent(innerContent);
        }

        return new ChooseSqlNode(whenContents, otherwiseContents);
    }

    /**
     * 创建文本节点
     * 如果包含 OGNL 表达式 (#{} 或 ${}) 则创建 TextSqlNode，否则创建 StaticTextSqlNode
     */
    private static SqlNode createTextNode(String text) {
        if (text.contains("#{") || text.contains("${")) {
            return new TextSqlNode(text);
        }
        return new StaticTextSqlNode(text);
    }

    /**
     * 查找下一个标签的开始位置
     */
    private static int findNextTagIndex(String content) {
        String[] tags = {"<if", "<where", "<set", "<trim", "<foreach", "<choose", "<when", "<otherwise", "<bind", "<script", "</script>"};
        int minIndex = -1;
        for (String tag : tags) {
            int index = content.indexOf(tag);
            if (index >= 0 && (minIndex < 0 || index < minIndex)) {
                minIndex = index;
            }
        }
        return minIndex;
    }

    /**
     * 查找配对的闭合标签位置
     */
    private static int findMatchingCloseTag(String content, int start, String openTag, String closeTag) {
        int depth = 1;
        int pos = start;

        while (depth > 0 && pos < content.length()) {
            // 检查是否有嵌套的相同标签
            int nextOpen = content.indexOf(openTag, pos);
            int nextClose = content.indexOf(closeTag, pos);

            if (nextClose < 0) {
                break;
            }

            if (nextOpen >= 0 && nextOpen < nextClose) {
                depth++;
                pos = nextOpen + openTag.length();
            } else {
                depth--;
                if (depth == 0) {
                    return nextClose;
                }
                pos = nextClose + closeTag.length();
            }
        }

        return -1;
    }

    /**
     * 内部类，用于存储解析出的节点和剩余内容
     */
    private static class SqlNodeAndRemaining {
        final SqlNode node;
        final String remaining;

        SqlNodeAndRemaining(SqlNode node, String remaining) {
            this.node = node;
            this.remaining = remaining;
        }
    }

    /**
     * IF 标签解析结果
     */
    private record IfTagResult(String test, String innerContent, String remaining) {
    }

    /**
     * TRIM 标签解析结果
     */
    private record TrimTagResult(String prefix, String suffix, String prefixOverrides,
                                 String suffixOverrides, String innerContent, String remaining) {
    }

    /**
     * FOREACH 标签解析结果
     */
    private record ForEachTagResult(String collection, String item, String index, String open,
                                    String close, String separator, String innerContent, String remaining) {
    }

    /**
     * BIND 标签解析结果
     */
    private record BindTagResult(String name, String expression, String remaining) {
    }
}