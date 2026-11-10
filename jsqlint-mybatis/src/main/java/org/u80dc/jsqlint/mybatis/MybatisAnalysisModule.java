package org.u80dc.jsqlint.mybatis;

import org.u80dc.jsqlint.core.spi.*;
import org.w3c.dom.*;
import sootup.core.jimple.common.constant.StringConstant;

import java.net.URI;
import java.util.*;

/**
 * Mapper XML 与静态 SQL 注解适配。每次 register 建立独立的 fragment 索引。
 * Provider、无法确定的 include 和不支持的标签显式保留为未解析起点。
 * @author u80dc
 */
public final class MybatisAnalysisModule implements SqlAnalysisModule {
    private static final Set<String> STATEMENTS = Set.of("select", "insert", "update", "delete");

    @Override
    public String id() { return "mybatis"; }

    @Override
    public void register(DiscoveryRegistry registry) {
        // 索引属于本次注册，复用模块实例也不会把上个项目的 SQL 片段带入当前分析。
        Map<String, List<Element>> fragments = new HashMap<>();
        MapperMethodMatcher methods = new MapperMethodMatcher();
        registry.sqlMethod(methods::isSqlMethod);
        registry.sqlInvocation(methods::isSqlInvocation);
        registry.resource(resource -> resource.name().toLowerCase(Locale.ROOT).endsWith(".xml"), (resource, seeds) -> {
            Document document = MybatisSqlAdapter.parseMapper(resource);
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
                else if (STATEMENTS.contains(element.getTagName())) {
                    methods.addStatement(namespace, element.getAttribute("id"));
                    seeds.accept(new MapperSeed(new SourceLocation(resource.uri(),
                            id + (element.hasAttribute("databaseId") ? ":" + element.getAttribute("databaseId") : "")),
                            element, namespace, fragments));
                }
            }
        });
        // 注解发现只读取方法元数据，接口中的抽象 Mapper 方法也能作为分析起点。
        registry.method(method -> true, (method, seeds) -> {
            for (var annotation : MapperMethodMatcher.sqlAnnotations(method)) {
                String type = annotation.getAnnotation().toString();
                String name = type.substring(type.lastIndexOf('.') + 1);
                Object value = annotation.getValues().get("value");
                var location = new SourceLocation(new URI("java", method.getDeclClassType().toString(), null),
                        method.getSignature().toString() + " @" + name);
                seeds.accept(new AnnotationSeed(location, name, value));
            }
        });
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
            return new SqlInput.Template(MybatisSqlAdapter.annotation(text));
        }
    }

    private record MapperSeed(SourceLocation location, Element statement, String namespace,
                              Map<String, List<Element>> fragments) implements AnalysisSeed {
        public SqlInput input() {
            // 等所有资源的片段进入索引后再展开，不依赖 Mapper 扫描顺序。
            return new SqlInput.Template(MybatisSqlAdapter.statement(statement, namespace, fragments));
        }
    }
}
