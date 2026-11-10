package org.u80dc.jsqlint.mybatis;

import org.u80dc.jsqlint.core.node.*;
import org.apache.ibatis.builder.ParameterExpression;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.type.TypeAliasRegistry;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.mapping.ParameterMode;
import org.u80dc.jsqlint.core.resource.AnalysisResource;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** MyBatis 的 XML、注解和 include 语义适配，共享 core 的通用节点构建与渲染。 @author u80dc */
final class MybatisSqlAdapter {
    private static final Map<String, Class<?>> BUILTIN_TYPES = Map.copyOf(new TypeAliasRegistry().getTypeAliases());
    private MybatisSqlAdapter() {}

    private static final Map<String, Set<String>> ATTRIBUTES = Map.ofEntries(
            Map.entry("script", Set.of()), Map.entry("if", Set.of("test")),
            Map.entry("where", Set.of()), Map.entry("set", Set.of()),
            Map.entry("trim", Set.of("prefix", "suffix", "prefixOverrides", "suffixOverrides")),
            Map.entry("foreach", Set.of("collection", "item", "index", "open", "close", "separator", "nullable")),
            Map.entry("bind", Set.of("name", "value")), Map.entry("choose", Set.of()),
            Map.entry("when", Set.of("test")), Map.entry("otherwise", Set.of()));

    static Document parseMapper(AnalysisResource resource) throws Exception {
        try (InputStream input = resource.openStream()) { return parse(input, true); }
    }

    static SqlNode annotation(String sql) {
        return annotation(sql, MybatisOptions.defaults());
    }

    static SqlNode annotation(String sql, MybatisOptions options) {
        if (!sql.stripLeading().startsWith("<script")) return text(expand(sql, options.variables()));
        try (var input = new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8))) {
            Element root = parse(input, false).getDocumentElement();
            validate(root, 0);
            return contents(root, options, options.variables(), 0);
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Cannot adapt MyBatis annotation script", e); }
    }

    static SqlNode statement(Element statement, String namespace, Map<String, List<Element>> fragments) {
        return statement(statement, namespace, fragments, MybatisOptions.defaults());
    }

    static SqlNode statement(Element statement, String namespace, Map<String, List<Element>> fragments, MybatisOptions options) {
        // 只复制语句内容，Mapper 的 id/resultMap 等元数据不进入通用 SQL 模型。
        Document document = statement.getOwnerDocument().getImplementation().createDocument(null, "script", null);
        Element script = document.getDocumentElement();
        copyContents(statement, script, namespace, fragments, new HashSet<>(), options.variables(), 0);
        validate(script, 0);
        return contents(script, options, Map.of(), 0);
    }

    /** Mapper 可声明 DTD；脚本禁止 DOCTYPE。两种模式均不访问外部实体、DTD 或网络。 */
    private static Document parse(InputStream input, boolean mapper) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", !mapper);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        builder.setErrorHandler(new DefaultHandler() {
            @Override public void error(SAXParseException e) throws SAXParseException { throw e; }
            @Override public void fatalError(SAXParseException e) throws SAXParseException { throw e; }
        });
        return builder.parse(input);
    }

    /** core 的 literal/value/skipBlank 属于通用 DSL，不扩大 MyBatis 的实际语法。 */
    private static void validate(Element element, int depth) {
        if (depth > 128) throw new IllegalArgumentException("MyBatis template depth exceeds 128");
        Set<String> allowed = ATTRIBUTES.get(element.getTagName());
        if (allowed == null) throw new IllegalArgumentException("Unsupported MyBatis tag: " + element.getTagName());
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            String attribute = element.getAttributes().item(i).getNodeName();
            if (!allowed.contains(attribute)) throw new IllegalArgumentException("Unsupported MyBatis attribute: " + attribute);
        }
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling())
            if (child instanceof Element nested) validate(nested, depth + 1);
    }

    private static void copyContents(Element source, Element target, String namespace,
                                     Map<String, List<Element>> fragments, Set<String> active, Map<String, String> variables, int depth) {
        if (depth > 128) throw new IllegalArgumentException("MyBatis template depth exceeds 128");
        Document document = target.getOwnerDocument();
        for (Node child = source.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && element.getTagName().equals("include")) {
                String reference = expand(element.getAttribute("refid"), variables);
                if (reference.contains("${")) throw new IllegalArgumentException("Unresolved MyBatis include: " + reference);
                Map<String, String> local = new LinkedHashMap<>(variables);
                Set<String> names = new HashSet<>();
                for (Node nested = element.getFirstChild(); nested != null; nested = nested.getNextSibling()) {
                    if (!(nested instanceof Element property)) {
                        if (nested.getNodeType() == Node.COMMENT_NODE || nested.getNodeValue().isBlank()) continue;
                        throw new IllegalArgumentException("MyBatis include accepts only property children");
                    }
                    if (!property.getTagName().equals("property") || property.getAttributes().getLength() != 2
                            || !property.hasAttribute("name") || !property.hasAttribute("value"))
                        throw new IllegalArgumentException("Invalid MyBatis include property");
                    String name = property.getAttribute("name");
                    if (!names.add(name)) throw new IllegalArgumentException("Duplicate MyBatis include property: " + name);
                    local.put(name, expand(property.getAttribute("value"), variables));
                }
                String id = reference.contains(".") ? reference : namespace + "." + reference;
                var candidates = fragments.getOrDefault(id, List.of());
                if (candidates.size() != 1 || !active.add(id))
                    throw new IllegalArgumentException("Missing, ambiguous or cyclic MyBatis include: " + id);
                Element fragment = candidates.getFirst();
                String fragmentNamespace = ((Element) fragment.getParentNode()).getAttribute("namespace");
                copyContents(fragment, target, fragmentNamespace, fragments, active, local, depth + 1);
                active.remove(id);
            } else if (child instanceof Element element) {
                Element copy = (Element) document.importNode(element, false);
                for (int i = 0; i < copy.getAttributes().getLength(); i++) {
                    Node attribute = copy.getAttributes().item(i);
                    attribute.setNodeValue(expand(attribute.getNodeValue(), variables));
                }
                target.appendChild(copy);
                copyContents(element, copy, namespace, fragments, active, variables, depth + 1);
            } else {
                Node copy = document.importNode(child, true);
                if (copy.getNodeType() == Node.TEXT_NODE || copy.getNodeType() == Node.CDATA_SECTION_NODE)
                    copy.setNodeValue(expand(copy.getNodeValue(), variables));
                target.appendChild(copy);
            }
        }
    }

    /** MyBatis 对每个文本/CDATA 节点调用 appendSql，并以空格连接。token 内部保持连续拼接。 */
    private static SqlNode contents(Element parent, MybatisOptions options, Map<String, String> variables, int depth) {
        return contents(parent, options, variables, depth, " ");
    }
    private static SqlNode contents(Element parent, MybatisOptions options, Map<String, String> variables, int depth, String separator) {
        if (depth > 128) throw new IllegalArgumentException("MyBatis template depth exceeds 128");
        List<SqlNode> nodes = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            switch (child.getNodeType()) {
                case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> nodes.add(text(expand(child.getNodeValue(), variables)));
                case Node.ELEMENT_NODE -> nodes.add(element((Element) child, options, variables, depth + 1, separator));
                case Node.COMMENT_NODE -> { }
                default -> throw new IllegalArgumentException("Unsupported MyBatis XML node in " + parent.getTagName());
            }
        }
        return nodes.size() == 1 ? nodes.getFirst() : new MixedSqlNode(nodes, separator);
    }

    private static SqlNode element(Element element, MybatisOptions options, Map<String, String> variables, int depth, String separator) {
        return switch (element.getTagName()) {
            case "if" -> new IfSqlNode(required(element, "test", variables), contents(element, options, variables, depth, separator));
            // TrimSqlNode 的过滤上下文使用 StringBuilder，内部 appendSql 不增加分隔符。
            case "where" -> new WhereSqlNode(contents(element, options, variables, depth, ""));
            case "set" -> new SetSqlNode(contents(element, options, variables, depth, ""));
            case "trim" -> new TrimSqlNode(expand(element.getAttribute("prefix"), variables), expand(element.getAttribute("suffix"), variables),
                    expand(element.getAttribute("prefixOverrides"), variables), expand(element.getAttribute("suffixOverrides"), variables),
                    contents(element, options, variables, depth, ""));
            case "foreach" -> new ForEachSqlNode(required(element, "collection", variables), element.getAttribute("item"),
                    element.getAttribute("index"), element.getAttribute("open").isEmpty() ? "" : element.getAttribute("open") + separator,
                    element.getAttribute("close").isEmpty() ? "" : separator + element.getAttribute("close"),
                    element.getAttribute("separator").isEmpty() ? separator : separator + element.getAttribute("separator") + separator,
                    contents(element, options, variables, depth, separator), flag(element, "nullable", options.nullableOnForEach()), true);
            case "bind" -> {
                if (!element.getTextContent().isBlank() || element.getElementsByTagName("*").getLength() != 0)
                    throw new IllegalArgumentException("MyBatis bind must have no body");
                yield new BindSqlNode(required(element, "name", variables), required(element, "value", variables));
            }
            case "choose" -> choose(element, options, variables, depth, separator);
            default -> throw new IllegalArgumentException("Unsupported or misplaced MyBatis tag: " + element.getTagName());
        };
    }
    private static SqlNode choose(Element element, MybatisOptions options, Map<String, String> variables, int depth, String separator) {
        List<IfSqlNode> branches = new ArrayList<>(); SqlNode otherwise = null;
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.COMMENT_NODE) continue;
            if (!(child instanceof Element branch)) {
                if (child.getNodeValue().isBlank()) continue;
                throw new IllegalArgumentException("MyBatis choose accepts only when and otherwise");
            }
            if (branch.getTagName().equals("when") && otherwise == null)
                branches.add(new IfSqlNode(required(branch, "test", variables), contents(branch, options, variables, depth + 1, separator)));
            else if (branch.getTagName().equals("otherwise") && otherwise == null)
                otherwise = contents(branch, options, variables, depth + 1, separator);
            else throw new IllegalArgumentException("MyBatis choose requires ordered when branches and one final otherwise");
        }
        if (branches.isEmpty()) throw new IllegalArgumentException("MyBatis choose requires at least one when");
        return new ChooseSqlNode(branches, otherwise);
    }
    private static SqlNode text(String source) { return bindingTypes(SqlNodeBuilder.templateText(source)); }
    private static SqlNode bindingTypes(SqlNode node) {
        if (node instanceof MixedSqlNode mixed) return new MixedSqlNode(mixed.contents().stream().map(MybatisSqlAdapter::bindingTypes).toList(), mixed.separator());
        if (!(node instanceof ValueSqlNode value) || value.getKind() != ValueSqlNode.Kind.PARAMETER) return node;
        var properties = new ParameterExpression(value.getExpression() + (value.getBindingOptions().isEmpty() ? "" : "," + value.getBindingOptions()));
        if (properties.containsKey("expression")) throw new IllegalArgumentException("MyBatis expression-based parameters are unsupported");
        var allowed = Set.of("property", "javaType", "jdbcType", "mode", "numericScale", "resultMap", "typeHandler", "jdbcTypeName");
        for (String key : properties.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("Unsupported MyBatis binding option: " + key);
        if (properties.containsKey("jdbcType")) JdbcType.valueOf(properties.get("jdbcType"));
        if (properties.containsKey("mode")) ParameterMode.valueOf(properties.get("mode"));
        if (properties.containsKey("numericScale")) Integer.parseInt(properties.get("numericScale"));
        String type = properties.getOrDefault("javaType", "");
        Class<?> alias = BUILTIN_TYPES.get(type.toLowerCase(Locale.ROOT));
        if (alias != null) type = alias.getTypeName();
        String bindingOptions = value.getBindingOptions();
        if (bindingOptions.isEmpty() && properties.containsKey("jdbcType")) bindingOptions = "jdbcType=" + properties.get("jdbcType");
        return ValueSqlNode.parameter(properties.get("property"), bindingOptions, type);
    }
    private static String required(Element element, String name, Map<String, String> variables) {
        String value = expand(element.getAttribute(name), variables);
        if (value.isBlank()) throw new IllegalArgumentException("MyBatis " + element.getTagName() + " requires " + name);
        return value;
    }
    private static boolean flag(Element element, String name, boolean fallback) {
        if (!element.hasAttribute(name)) return fallback;
        return switch (element.getAttribute(name)) {
            case "true" -> true; case "false" -> false;
            default -> throw new IllegalArgumentException("MyBatis " + name + " must be true or false");
        };
    }
    private static String expand(String value, Map<String, String> variables) {
        Properties properties = new Properties(); properties.putAll(variables);
        return PropertyParser.parse(value, properties);
    }
}
