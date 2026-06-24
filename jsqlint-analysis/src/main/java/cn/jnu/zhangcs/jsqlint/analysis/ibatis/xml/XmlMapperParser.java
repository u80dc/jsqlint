package cn.jnu.zhangcs.jsqlint.analysis.ibatis.xml;

import cn.jnu.zhangcs.jsqlint.analysis.StatementType;
import cn.jnu.zhangcs.jsqlint.analysis.ibatis.MappedStatement;
import lombok.extern.slf4j.Slf4j;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.*;

/**
 * MyBatis XML Mapper 解析器
 *
 * @author zhangcs
 */
@Slf4j
public class XmlMapperParser {

    /**
     * 命名空间，用于映射接口类
     */
    private static final String NAMESPACE_ATTR = "namespace";

    /**
     * 语句ID标识，用于映射接口方法
     */
    private static final String ID_ATTR = "id";

    /**
     * include 标签的 refid 属性
     */
    private static final String REFID_ATTR = "refid";

    /**
     * SQL 标签名称
     */
    private static final String SQL_TAG = "sql";

    /**
     * INCLUDE 标签名称
     */
    private static final String INCLUDE_TAG = "include";

    /**
     * IF 标签名称
     */
    private static final String IF_TAG = "if";

    /**
     * PROPERTY 标签名称
     */
    private static final String PROPERTY_TAG = "property";

    /**
     * WHERE 标签名称
     */
    private static final String WHERE_TAG = "where";

    /**
     * SET 标签名称
     */
    private static final String SET_TAG = "set";

    /**
     * TRIM 标签名称
     */
    private static final String TRIM_TAG = "trim";

    /**
     * FOREACH 标签名称
     */
    private static final String FOREACH_TAG = "foreach";

    /**
     * CHOOSE 标签名称
     */
    private static final String CHOOSE_TAG = "choose";

    /**
     * WHEN 标签名称
     */
    private static final String WHEN_TAG = "when";

    /**
     * OTHERWISE 标签名称
     */
    private static final String OTHERWISE_TAG = "otherwise";

    /**
     * BIND 标签名称
     */
    private static final String BIND_TAG = "bind";

    /**
     * 解析
     *
     * @param file 需要解析的XML文件
     * @return 返回解析后的结果对象
     */
    @Nullable
    public static XmlMapper parse(Path file) {
        try (InputStream inputStream = Files.newInputStream(file)) {
            return parse(inputStream, file.toAbsolutePath().normalize().toString());
        } catch (NoSuchFileException e) {
            log.warn("文件不存在: {}", file.toAbsolutePath());
        } catch (Exception e) {
            log.warn("文件解析失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 解析
     *
     * @param inputStream 需要解析的XML文件输入流
     * @return 返回解析后的结果对象
     * @throws Exception 解析失败时抛出此异常
     */
    private static @NonNull XmlMapper parse(InputStream inputStream, String sourceFile) throws Exception {
        SAXReader reader = new SAXReader();
        Document document = reader.read(inputStream);
        Element root = document.getRootElement();

        // 读取命名空间
        String namespace = root.attributeValue(NAMESPACE_ATTR);

        // 第一遍：收集所有 sql 标签（预解析为 Element）
        Map<String, Element> sqlFragments = collectSqlFragments(root);

        // 第二遍：解析语句，并展开 include 标签
        List<MappedStatement> statements = parseStatements(root, sqlFragments);

        return new XmlMapper(namespace, statements, sourceFile);
    }

    /**
     * 收集所有 sql 标签（预解析为 Element）
     *
     * @param root 根节点
     * @return sql ID -> sql 元素
     */
    private static Map<String, Element> collectSqlFragments(Element root) {
        Map<String, Element> result = new HashMap<>();
        collectSqlFragmentsRecursive(root, result);
        return result;
    }

    /**
     * 递归收集 sql 标签
     *
     * @param element      节点
     * @param sqlFragments sql标签结果
     */
    private static void collectSqlFragmentsRecursive(Element element, Map<String, Element> sqlFragments) {
        for (Object child : element.elements()) {
            if (child instanceof Element childElement) {
                String name = childElement.getName().toLowerCase();
                if (SQL_TAG.equals(name)) {
                    String id = childElement.attributeValue(ID_ATTR);
                    if (id != null && !id.isEmpty()) {
                        sqlFragments.put(id, childElement);
                    }
                }
                collectSqlFragmentsRecursive(childElement, sqlFragments);
            }
        }
    }

    /**
     * 解析语句定义
     *
     * @param root 根节点
     * @param sqlFragments sql 标签片段映射
     * @return 返回语句定义列表
     */
    private static @NonNull List<MappedStatement> parseStatements(Element root, Map<String, Element> sqlFragments) {
        List<Element> elements = root.elements();
        if (elements.isEmpty()) {
            return List.of();
        }

        List<MappedStatement> statements = new ArrayList<>();
        for (Element child : elements) {
            String name = child.getName().toLowerCase();
            StatementType type = switch (name) {
                case "select" -> StatementType.SELECT;
                case "insert" -> StatementType.INSERT;
                case "update" -> StatementType.UPDATE;
                case "delete" -> StatementType.DELETE;
                default -> null;
            };

            if (type != null) {
                String id = child.attributeValue(ID_ATTR);
                MappedStatement statement = new MappedStatement(id, type,
                        extractRawSql(child, sqlFragments));
                statements.add(statement);
            }

            statements.addAll(parseStatements(child, sqlFragments));
        }
        return statements;
    }

    /**
     * 提取原始SQL文本（支持展开 include 标签）
     *
     * @param element      定义节点
     * @param sqlFragments sql 标签片段映射
     * @return 返回展开后的文本
     */
    private static String extractRawSql(Element element, Map<String, Element> sqlFragments) {
        return extractRawSql(element, sqlFragments, new HashMap<>(), new HashSet<>());
    }

    /**
     * 提取原始SQL文本（支持展开 include 标签，支持属性传递和循环引用检测）
     *
     * @param element      定义节点
     * @param sqlFragments sql 标签片段映射
     * @param properties   当前上下文传入的属性
     * @param visitingSet  当前正在访问的 sql ID 集合（用于检测循环引用）
     * @return 返回展开后的文本
     */
    private static String extractRawSql(Element element, Map<String, Element> sqlFragments,
                                        Map<String, String> properties, Set<String> visitingSet) {
        StringBuilder sb = new StringBuilder();
        for (Object node : element.content()) {
            if (node instanceof org.dom4j.Text text) {
                sb.append(text.getText());
            } else if (node instanceof Element child) {
                String name = child.getName().toLowerCase();
                if (INCLUDE_TAG.equals(name)) {
                    // 处理 include 标签
                    String refId = child.attributeValue(REFID_ATTR);
                    if (refId != null && sqlFragments.containsKey(refId)) {
                        // 收集 include 标签中传入的属性
                        Map<String, String> includeProperties = new HashMap<>(properties);
                        collectProperties(child, includeProperties);

                        // 展开 sql 片段
                        Element sqlElement = sqlFragments.get(refId);
                        String expandedSql = expandSqlElement(sqlElement, sqlFragments, includeProperties, visitingSet);
                        sb.append(expandedSql);
                    }
                } else if (IF_TAG.equals(name)) {
                    sb.append("<if test=\"").append(child.attributeValue("test")).append("\">");
                    sb.append(extractRawSql(child, sqlFragments, properties, visitingSet));
                    sb.append("</if>");
                } else if (isDynamicTag(name)) {
                    // 处理 MyBatis 动态标签
                    sb.append("<").append(name);
                    child.attributes().forEach(attr ->
                            sb.append(" ").append(attr.getName()).append("=\"").append(attr.getValue()).append("\"")
                    );
                    sb.append(">");
                    sb.append(extractRawSql(child, sqlFragments, properties, visitingSet));
                    sb.append("</").append(name).append(">");
                } else if (BIND_TAG.equals(name)) {
                    // 处理 bind 标签
                    sb.append("<bind");
                    child.attributes().forEach(attr ->
                            sb.append(" ").append(attr.getName()).append("=\"").append(attr.getValue()).append("\"")
                    );
                    sb.append("/>");
                } else {
                    sb.append(extractRawSql(child, sqlFragments, properties, visitingSet));
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * 检查是否为 MyBatis 动态标签
     */
    private static boolean isDynamicTag(String name) {
        return WHERE_TAG.equals(name) || SET_TAG.equals(name) ||
                TRIM_TAG.equals(name) || FOREACH_TAG.equals(name) ||
                CHOOSE_TAG.equals(name) || WHEN_TAG.equals(name) || OTHERWISE_TAG.equals(name);
    }

    /**
     * 收集 include 标签中的 property 属性
     */
    private static void collectProperties(Element includeElement, Map<String, String> properties) {
        for (Object child : includeElement.elements()) {
            if (child instanceof Element propElement) {
                String propName = propElement.getName().toLowerCase();
                if (PROPERTY_TAG.equals(propName)) {
                    String name = propElement.attributeValue("name");
                    String value = propElement.attributeValue("value");
                    if (name != null && value != null) {
                        properties.put(name, value);
                    }
                }
            }
        }
    }

    /**
     * 展开 sql 元素的内容，支持递归展开 include 标签
     *
     * @param sqlElement   sql 元素
     * @param sqlFragments sql 标签片段映射
     * @param properties   当前上下文传入的属性
     * @param visitingSet  当前正在访问的 sql ID 集合（用于检测循环引用）
     * @return 展开后的内容
     */
    private static String expandSqlElement(Element sqlElement, Map<String, Element> sqlFragments,
                                           Map<String, String> properties, Set<String> visitingSet) {
        // 获取当前 sql 的 ID 用于循环检测
        String sqlId = sqlElement.attributeValue(ID_ATTR);
        if (sqlId != null && visitingSet.contains(sqlId)) {
            log.debug("Detected circular reference in sql fragment: {}", sqlId);
            return "";
        }

        // 添加到访问集合
        Set<String> newVisitingSet = new HashSet<>(visitingSet);
        if (sqlId != null) {
            newVisitingSet.add(sqlId);
        }

        StringBuilder sb = new StringBuilder();
        for (Object node : sqlElement.content()) {
            if (node instanceof org.dom4j.Text text) {
                String content = text.getText();
                sb.append(replaceProperties(content, properties));
            } else if (node instanceof Element child) {
                String name = child.getName().toLowerCase();
                if (INCLUDE_TAG.equals(name)) {
                    // 递归展开 include
                    String refid = child.attributeValue(REFID_ATTR);
                    if (refid != null && sqlFragments.containsKey(refid)) {
                        // 收集 include 中的属性（会覆盖已有属性）
                        Map<String, String> nestedProperties = new HashMap<>(properties);
                        collectProperties(child, nestedProperties);

                        // 递归展开
                        Element nestedSql = sqlFragments.get(refid);
                        sb.append(expandSqlElement(nestedSql, sqlFragments, nestedProperties, newVisitingSet));
                    }
                } else if (IF_TAG.equals(name)) {
                    sb.append("<if test=\"").append(child.attributeValue("test")).append("\">");
                    sb.append(expandSqlElement(child, sqlFragments, properties, newVisitingSet));
                    sb.append("</if>");
                } else if (CHOOSE_TAG.equals(name)) {
                    sb.append("<choose>");
                    sb.append(expandSqlElement(child, sqlFragments, properties, newVisitingSet));
                    sb.append("</choose>");
                } else if (WHEN_TAG.equals(name)) {
                    sb.append("<when test=\"").append(child.attributeValue("test")).append("\">");
                    sb.append(expandSqlElement(child, sqlFragments, properties, newVisitingSet));
                    sb.append("</when>");
                } else if (OTHERWISE_TAG.equals(name)) {
                    sb.append("<otherwise>");
                    sb.append(expandSqlElement(child, sqlFragments, properties, newVisitingSet));
                    sb.append("</otherwise>");
                } else if (isDynamicTag(name)) {
                    sb.append("<").append(name);
                    child.attributes().forEach(attr ->
                            sb.append(" ").append(attr.getName()).append("=\"").append(attr.getValue()).append("\"")
                    );
                    sb.append(">");
                    sb.append(expandSqlElement(child, sqlFragments, properties, newVisitingSet));
                    sb.append("</").append(name).append(">");
                } else if (BIND_TAG.equals(name)) {
                    // 处理 bind 标签
                    sb.append("<bind");
                    child.attributes().forEach(attr ->
                            sb.append(" ").append(attr.getName()).append("=\"").append(attr.getValue()).append("\"")
                    );
                    sb.append("/>");
                } else {
                    sb.append(expandSqlElement(child, sqlFragments, properties, newVisitingSet));
                }
            }
        }
        return sb.toString();
    }

    /**
     * 替换 ${propertyName} 为实际值
     *
     * @param content    原始内容
     * @param properties 属性映射
     * @return 替换后的内容
     */
    private static String replaceProperties(String content, Map<String, String> properties) {
        if (content == null || properties == null || properties.isEmpty()) {
            return content;
        }

        String result = content;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String placeholder = "${" + entry.getKey() + "}";
            result = result.replace(placeholder, entry.getValue());
        }
        return result;
    }

}
