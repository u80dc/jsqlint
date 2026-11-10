package org.u80dc.jsqlint.core.node;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;

/**
 * 普通 SQL 原样保存；XML 脚本使用 script 根元素或显式的 parseScript 入口。
 * XML 模式遵循实体、CDATA 和换行的 XML 语义，不能接受未转义的 XML 文本。
 *
 * @author u80dc
 */
public final class SqlNodeBuilder {
    private SqlNodeBuilder() {
    }

    /**
     * 只自动识别开头的 script 根元素，其他输入始终作为普通 SQL 文本。
     */
    public static SqlNode build(String source) {
        Objects.requireNonNull(source, "source");
        return source.stripLeading().matches("(?s)<script(?:\\s|/?>).*")
                ? buildScript(source) : text(source);
    }

    /**
     * 不解释 XML 标签或 MyBatis 占位符，包括字符串中的这些文本。
     */
    public static SqlNode text(String sql) {
        return new StaticTextSqlNode(sql);
    }

    /**
     * 只解释占位符，不解释 XML；非法占位符抛 IllegalArgumentException，不返回部分树。
     */
    public static SqlNode templateText(String source) {
        return SqlTemplateText.parse(source);
    }

    /**
     * 解析完整 script 或 XML 片段；失败抛异常，禁止返回部分解析的树。
     */
    public static SqlNode buildScript(String source) {
        ParseResult result = parseScript(source);
        return result.node().orElseThrow(() -> new SqlNodeParseException(result));
    }

    public static ParseResult parseScript(String source) {
        Objects.requireNonNull(source, "source");
        boolean wrapped = !source.stripLeading().matches("(?s)<script(?:\\s|/?>).*");
        String xml = wrapped ? "<script>" + source + "</script>" : source;
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException e) throws SAXParseException {
                    throw e;
                }

                @Override
                public void fatalError(SAXParseException e) throws SAXParseException {
                    throw e;
                }
            });
            Element root = builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
            return new ParseResult(source, Optional.of(fromScriptElement(root)), List.of());
        } catch (SAXParseException e) {
            int column = wrapped && e.getLineNumber() == 1
                    ? Math.max(1, e.getColumnNumber() - 8) : e.getColumnNumber();
            return failure(source, "XML_PARSE_ERROR", "line " + e.getLineNumber()
                    + ", column " + column + ": " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return failure(source, "INVALID_SCRIPT", e.getMessage());
        } catch (Exception e) {
            return failure(source, "XML_PARSE_ERROR", e.getMessage());
        }
    }

    /**
     * 将已解析的通用 script DOM 转成节点，不序列化或再次解析 XML。
     * 框架适配层应先完成自己的标签校验和语义转换；此方法不修改输入 DOM。
     */
    public static SqlNode fromScriptElement(Element root) {
        Objects.requireNonNull(root, "root");
        if (!"script".equals(root.getTagName())) throw new IllegalArgumentException("Expected a script root element");
        attributes(root, Set.of());
        return contents(root, 0);
    }

    private static ParseResult failure(String source, String code, String message) {
        return new ParseResult(source, Optional.empty(),
                List.of(new SqlDiagnostic(code, message == null ? code : message)));
    }

    private static SqlNode contents(Element parent, int depth) {
        if (depth > 128) {
            throw new IllegalArgumentException("SQL template nesting exceeds 128 levels");
        }
        List<SqlNode> nodes = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            switch (child.getNodeType()) {
                case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> text.append(child.getNodeValue());
                case Node.ELEMENT_NODE -> {
                    flushText(nodes, text);
                    nodes.add(element((Element) child, depth + 1));
                }
                case Node.COMMENT_NODE -> { /* XML comments are not SQL text. */ }
                default -> throw new IllegalArgumentException("Unsupported XML node in " + parent.getTagName());
            }
        }
        flushText(nodes, text);
        return nodes.size() == 1 ? nodes.getFirst() : new MixedSqlNode(nodes);
    }

    private static void flushText(List<SqlNode> nodes, StringBuilder text) {
        if (!text.isEmpty()) {
            nodes.add(templateText(text.toString()));
            text.setLength(0);
        }
    }

    private static SqlNode element(Element element, int depth) {
        return switch (element.getTagName()) {
            case "literal" -> {
                attributes(element, Set.of());
                for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (child.getNodeType() != Node.TEXT_NODE && child.getNodeType() != Node.CDATA_SECTION_NODE
                            && child.getNodeType() != Node.COMMENT_NODE) {
                        throw new IllegalArgumentException("literal accepts only XML text or CDATA");
                    }
                }
                yield new StaticTextSqlNode(element.getTextContent());
            }
            case "value" -> {
                attributes(element, Set.of("expression", "nullText"));
                if (!element.getTextContent().isBlank() || element.getElementsByTagName("*").getLength() != 0) {
                    throw new IllegalArgumentException("value must have no body");
                }
                yield ValueSqlNode.raw(required(element, "expression"), element.getAttribute("nullText"));
            }
            case "if" -> {
                attributes(element, Set.of("test"));
                yield new IfSqlNode(required(element, "test"), contents(element, depth));
            }
            case "where" -> {
                attributes(element, Set.of());
                yield new WhereSqlNode(contents(element, depth));
            }
            case "set" -> {
                attributes(element, Set.of());
                yield new SetSqlNode(contents(element, depth));
            }
            case "trim" -> {
                attributes(element, Set.of("prefix", "suffix", "prefixOverrides", "suffixOverrides"));
                yield new TrimSqlNode(element.getAttribute("prefix"), element.getAttribute("suffix"),
                        element.getAttribute("prefixOverrides"), element.getAttribute("suffixOverrides"),
                        contents(element, depth));
            }
            case "foreach" -> {
                attributes(element, Set.of("collection", "item", "index", "open", "close", "separator", "nullable", "skipBlank"));
                yield new ForEachSqlNode(required(element, "collection"), element.getAttribute("item"),
                        element.getAttribute("index"), element.getAttribute("open"),
                        element.getAttribute("close"), element.getAttribute("separator"),
                        contents(element, depth), flag(element, "nullable", false), flag(element, "skipBlank", true));
            }
            case "bind" -> {
                attributes(element, Set.of("name", "value"));
                if (!element.getTextContent().isBlank() || element.getElementsByTagName("*").getLength() != 0) {
                    throw new IllegalArgumentException("bind must have no body");
                }
                yield new BindSqlNode(required(element, "name"), required(element, "value"));
            }
            case "choose" -> choose(element, depth);
            default -> throw new IllegalArgumentException("Unsupported or misplaced tag: " + element.getTagName());
        };
    }

    private static ChooseSqlNode choose(Element element, int depth) {
        attributes(element, Set.of());
        List<IfSqlNode> branches = new ArrayList<>();
        SqlNode otherwise = null;
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.COMMENT_NODE) {
                continue;
            }
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                if (!child.getNodeValue().isBlank()) {
                    throw new IllegalArgumentException("choose accepts only when and otherwise");
                }
                continue;
            }
            if (!(child instanceof Element branch)) {
                throw new IllegalArgumentException("Unsupported XML node in choose");
            }
            if ("when".equals(branch.getTagName()) && otherwise == null) {
                attributes(branch, Set.of("test"));
                branches.add(new IfSqlNode(required(branch, "test"), contents(branch, depth + 1)));
            } else if ("otherwise".equals(branch.getTagName()) && otherwise == null) {
                attributes(branch, Set.of());
                otherwise = contents(branch, depth + 1);
            } else {
                throw new IllegalArgumentException("choose requires ordered when branches and at most one final otherwise");
            }
        }
        if (branches.isEmpty()) {
            throw new IllegalArgumentException("choose requires at least one when");
        }
        return new ChooseSqlNode(branches, otherwise);
    }

    private static void attributes(Element element, Set<String> allowed) {
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            String name = element.getAttributes().item(i).getNodeName();
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("Unsupported attribute " + name + " on " + element.getTagName());
            }
        }
    }

    private static String required(Element element, String name) {
        String value = element.getAttribute(name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(element.getTagName() + " requires " + name);
        }
        return value;
    }

    private static boolean flag(Element element, String name, boolean fallback) {
        if (!element.hasAttribute(name)) {
            return fallback;
        }
        return switch (element.getAttribute(name)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(element.getTagName() + " requires true or false for " + name);
        };
    }

    /**
     * 失败时 node 为空；source 保留完整输入供诊断或重试。
     */
    public record ParseResult(String source, Optional<SqlNode> node, List<SqlDiagnostic> diagnostics) {
        public ParseResult {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(node, "node");
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean successful() {
            return node.isPresent();
        }
    }
}
