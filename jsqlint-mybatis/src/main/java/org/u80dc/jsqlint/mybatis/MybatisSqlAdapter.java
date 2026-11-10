package org.u80dc.jsqlint.mybatis;

import org.u80dc.jsqlint.core.node.SqlNode;
import org.u80dc.jsqlint.core.node.SqlNodeBuilder;
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
        if (!sql.stripLeading().startsWith("<script")) return SqlNodeBuilder.templateText(sql);
        try (var input = new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8))) {
            Element root = parse(input, false).getDocumentElement();
            validate(root, 0);
            return SqlNodeBuilder.fromScriptElement(root);
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Cannot adapt MyBatis annotation script", e); }
    }

    static SqlNode statement(Element statement, String namespace, Map<String, List<Element>> fragments) {
        // 只复制语句内容，Mapper 的 id/resultMap 等元数据不进入通用 SQL 模型。
        Document document = statement.getOwnerDocument().getImplementation().createDocument(null, "script", null);
        Element script = document.getDocumentElement();
        copyContents(statement, script, namespace, fragments, new HashSet<>(), 0);
        validate(script, 0);
        return SqlNodeBuilder.fromScriptElement(script);
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
                                     Map<String, List<Element>> fragments, Set<String> active, int depth) {
        if (depth > 128) throw new IllegalArgumentException("MyBatis template depth exceeds 128");
        Document document = target.getOwnerDocument();
        for (Node child = source.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && element.getTagName().equals("include")) {
                String reference = element.getAttribute("refid");
                if (reference.contains("$") || element.getElementsByTagName("property").getLength() > 0)
                    throw new IllegalArgumentException("Dynamic MyBatis include properties need explicit expansion");
                String id = reference.contains(".") ? reference : namespace + "." + reference;
                var candidates = fragments.getOrDefault(id, List.of());
                if (candidates.size() != 1 || !active.add(id))
                    throw new IllegalArgumentException("Missing, ambiguous or cyclic MyBatis include: " + id);
                Element fragment = candidates.getFirst();
                String fragmentNamespace = ((Element) fragment.getParentNode()).getAttribute("namespace");
                copyContents(fragment, target, fragmentNamespace, fragments, active, depth + 1);
                active.remove(id);
            } else if (child instanceof Element element) {
                Element copy = (Element) document.importNode(element, false);
                target.appendChild(copy);
                copyContents(element, copy, namespace, fragments, active, depth + 1);
            } else target.appendChild(document.importNode(child, true));
        }
    }
}
