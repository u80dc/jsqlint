package org.u80dc.jsqlint.mybatis;

import org.u80dc.jsqlint.core.node.SqlNodeBuilder;
import org.u80dc.jsqlint.core.spi.*;
import org.u80dc.jsqlint.core.resource.AnalysisResource;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;
import sootup.core.jimple.common.constant.StringConstant;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.net.URI;
import java.util.*;

/**
 * Mapper XML 与静态 SQL 注解适配。每次 register 建立独立的 fragment 索引。
 * Provider、无法确定的 include 和不支持的标签显式保留为未解析起点。
 * @author u80dc
 */
public final class MybatisAnalysisModule implements SqlAnalysisModule {
    private static final Set<String> STATEMENTS = Set.of("select", "insert", "update", "delete");
    private static final Set<String> ANNOTATIONS = Set.of("Select", "Insert", "Update", "Delete");

    @Override
    public String id() { return "mybatis"; }

    @Override
    public void register(DiscoveryRegistry registry) {
        // 索引属于本次注册，复用模块实例也不会把上个项目的 SQL 片段带入当前分析。
        Map<String, List<Element>> fragments = new HashMap<>();
        registry.resource(resource -> resource.name().toLowerCase(Locale.ROOT).endsWith(".xml"), (resource, seeds) -> {
            Document document = parse(resource);
            Element mapper = document.getDocumentElement();
            // 扩展名仅作初筛，普通 XML 文件不属于 Mapper 资源。
            if (!mapper.getTagName().equals("mapper")) return;
            String namespace = mapper.getAttribute("namespace");
            for (Node child = mapper.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!(child instanceof Element element)) continue;
                String id = namespace + "." + element.getAttribute("id");
                // 同名片段全部保留，留到引用解析时判断歧义，不能静默覆盖其中一个。
                if (element.getTagName().equals("sql"))
                    fragments.computeIfAbsent(id, ignored -> new ArrayList<>()).add(element);
                else if (STATEMENTS.contains(element.getTagName()))
                    seeds.accept(new MapperSeed(new SourceLocation(resource.uri(),
                            id + (element.hasAttribute("databaseId") ? ":" + element.getAttribute("databaseId") : "")),
                            element, namespace, fragments));
            }
        });
        // 注解发现只读取方法元数据，接口中的抽象 Mapper 方法也能作为分析起点。
        registry.method(method -> true, (method, seeds) -> {
            for (var annotation : method.getAnnotations()) {
                String type = annotation.getAnnotation().toString();
                if (!type.startsWith("org.apache.ibatis.annotations.")) continue;
                String name = type.substring(type.lastIndexOf('.') + 1);
                if (!ANNOTATIONS.contains(name) && !name.endsWith("Provider")) continue;
                Object value = annotation.getValues().get("value");
                var location = new SourceLocation(new URI("java", method.getDeclClassType().toString(), null),
                        method.getSignature().toString() + " @" + name);
                seeds.accept(new AnnotationSeed(location, name, value));
            }
        });
    }

    private static Document parse(AnalysisResource resource) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        // 允许 Mapper 常见的 DOCTYPE 声明，但不加载外部 DTD、实体或网络资源。
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
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
        try (InputStream input = resource.openStream()) { return builder.parse(input); }
    }

    private record AnnotationSeed(SourceLocation location, String annotation, Object value) implements AnalysisSeed {
        public SqlInput input() {
            // Provider 仍作为已发现起点保留，能力不足由 core 转为未知节点并记录部分完成。
            if (annotation.endsWith("Provider"))
                throw new IllegalArgumentException("MyBatis Provider requires a provider-method adapter");
            List<?> values = value instanceof List<?> list ? list : Collections.singletonList(value);
            List<String> sql = new ArrayList<>();
            for (Object item : values) {
                if (item instanceof StringConstant constant) sql.add(constant.getValue());
                else if (item instanceof String string) sql.add(string);
                else throw new IllegalArgumentException("Unresolved SQL annotation value: " + value);
            }
            // 注解中的多段字符串按 MyBatis 约定以空格连接；仅 script 内容按 XML 标签解析。
            String text = String.join(" ", sql);
            return new SqlInput.Template(text.stripLeading().startsWith("<script")
                    ? SqlNodeBuilder.buildScript(text) : SqlNodeBuilder.templateText(text));
        }
    }

    private record MapperSeed(SourceLocation location, Element statement, String namespace,
                              Map<String, List<Element>> fragments) implements AnalysisSeed {
        public SqlInput input() {
            try {
                // 发现阶段结束后才展开，此时其他文件的片段已经入索引，不依赖文件扫描顺序。
                Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
                Element script = document.createElement("script");
                document.appendChild(script);
                copyContents(statement, script, namespace, fragments, new HashSet<>(), 0);
                var factory = TransformerFactory.newInstance();
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
                var transformer = factory.newTransformer();
                transformer.setOutputProperty("omit-xml-declaration", "yes");
                StringWriter writer = new StringWriter();
                // 由 XML 序列化器保留文本、CDATA 和转义语义，再交给 core 构建通用节点。
                transformer.transform(new DOMSource(script), new StreamResult(writer));
                return new SqlInput.Template(SqlNodeBuilder.buildScript(writer.toString()));
            } catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new IllegalArgumentException("Cannot adapt mapper statement", e); }
        }
    }

    private static void copyContents(Element source, Element target, String namespace,
                                     Map<String, List<Element>> fragments, Set<String> active, int depth) {
        // 标签嵌套和 include 展开共同计入深度，限制递归栈及模板展开成本。
        if (depth > 128) throw new IllegalArgumentException("MyBatis template depth exceeds 128");
        Document document = target.getOwnerDocument();
        for (Node child = source.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && element.getTagName().equals("include")) {
                String reference = element.getAttribute("refid");
                if (reference.contains("$") || element.getElementsByTagName("property").getLength() > 0)
                    throw new IllegalArgumentException("Dynamic MyBatis include properties need explicit expansion");
                String id = reference.contains(".") ? reference : namespace + "." + reference;
                var candidates = fragments.getOrDefault(id, List.of());
                // active 只记录当前引用链，允许其他位置重复引用，但拒绝循环和多候选歧义。
                if (candidates.size() != 1 || !active.add(id))
                    throw new IllegalArgumentException("Missing, ambiguous or cyclic MyBatis include: " + id);
                Element fragment = candidates.getFirst();
                // 跨 Mapper 引用后，相对 refid 应使用被引用片段自身的 namespace。
                String fragmentNamespace = ((Element) fragment.getParentNode()).getAttribute("namespace");
                copyContents(fragment, target, fragmentNamespace, fragments, active, depth + 1);
                active.remove(id);
            } else if (child instanceof Element element) {
                Element copy = (Element) document.importNode(element, false);
                target.appendChild(copy);
                copyContents(element, copy, namespace, fragments, active, depth + 1);
            } else {
                target.appendChild(document.importNode(child, true));
            }
        }
    }
}
