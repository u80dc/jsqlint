package org.u80dc.jsqlint.core.node;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 文本语法适配：占位符只在这里解释，渲染器只处理节点语义。
 *
 * @author u80dc
 *
 */
final class SqlTemplateText {
    private SqlTemplateText() {
    }

    static SqlNode parse(String source) {
        Objects.requireNonNull(source, "source");
        List<SqlNode> nodes = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < source.length(); ) {
            if (source.charAt(i) == '\\' && placeholderAt(source, i + 1)) {
                int end = tokenEnd(source, i + 3);
                end = end < 0 ? source.length() : end + 1;
                literal.append(source, i + 1, end);
                i = end;
            } else if (placeholderAt(source, i)) {
                int end = tokenEnd(source, i + 2);
                if (end < 0) {
                    throw new IllegalArgumentException("Unclosed placeholder: " + source.substring(i));
                }
                flush(nodes, literal);
                String body = source.substring(i + 2, end);
                if (source.charAt(i) == '#') {
                    int options = optionsStart(body);
                    nodes.add(options < 0 ? ValueSqlNode.parameter(body)
                            : ValueSqlNode.parameter(body.substring(0, options), body.substring(options + 1)));
                } else {
                    nodes.add(ValueSqlNode.raw(body));
                }
                i = end + 1;
            } else {
                literal.append(source.charAt(i++));
            }
        }
        flush(nodes, literal);
        return nodes.isEmpty() ? new StaticTextSqlNode("")
                : nodes.size() == 1 ? nodes.getFirst() : new MixedSqlNode(nodes);
    }

    private static boolean placeholderAt(String source, int offset) {
        return offset + 1 < source.length()
                && (source.charAt(offset) == '$' || source.charAt(offset) == '#')
                && source.charAt(offset + 1) == '{';
    }

    private static void flush(List<SqlNode> nodes, StringBuilder literal) {
        if (!literal.isEmpty()) {
            nodes.add(new StaticTextSqlNode(literal.toString()));
            literal.setLength(0);
        }
    }

    private static int tokenEnd(String source, int start) {
        char quote = 0;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '}') {
                return i;
            }
        }
        return -1;
    }

    private static int optionsStart(String source) {
        char quote = 0;
        int brackets = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '[' || c == '(') {
                brackets++;
            } else if (c == ']' || c == ')') {
                brackets--;
            } else if (c == ',' && brackets == 0) {
                return i;
            }
        }
        return -1;
    }
}
