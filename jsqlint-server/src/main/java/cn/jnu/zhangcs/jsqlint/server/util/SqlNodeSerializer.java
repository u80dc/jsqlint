package cn.jnu.zhangcs.jsqlint.server.util;

import cn.jnu.zhangcs.jsqlint.analysis.node.*;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SqlNode 序列化工具类
 * 将 SqlNode 树结构序列化为 JSON Map，用于存储到数据库
 *
 * @author zhangcs
 */
public class SqlNodeSerializer {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 将 SqlNode 序列化为 Map
     */
    public static Map<String, Object> serialize(SqlNode node) {
        if (node == null) {
            return null;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", node.getType().name());
        result.put("isDynamic", node.isDynamic());

        switch (node.getType()) {
            case STATIC_TEXT -> serializeStaticText((StaticTextSqlNode) node, result);
            case TEXT -> serializeText((TextSqlNode) node, result);
            case IF -> serializeIf((IfSqlNode) node, result);
            case FOREACH -> serializeForeach((ForEachSqlNode) node, result);
            case CHOOSE -> serializeChoose((ChooseSqlNode) node, result);
            case TRIM -> serializeTrim((TrimSqlNode) node, result);
            case SET -> serializeSet((SetSqlNode) node, result);
            case WHERE -> serializeWhere((WhereSqlNode) node, result);
            case BIND -> serializeBind((BindSqlNode) node, result);
            case MIXED -> serializeMixed((MixedSqlNode) node, result);
            default -> {
                // 其他类型暂不处理
            }
        }

        return result;
    }

    private static void serializeStaticText(StaticTextSqlNode node, Map<String, Object> result) {
        result.put("text", node.getText());
    }

    private static void serializeText(TextSqlNode node, Map<String, Object> result) {
        result.put("text", node.getText());
    }

    private static void serializeIf(IfSqlNode node, Map<String, Object> result) {
        result.put("test", node.getTest());
        result.put("contents", serialize(node.getContents()));
    }

    private static void serializeForeach(ForEachSqlNode node, Map<String, Object> result) {
        result.put("collection", node.getCollection());
        result.put("item", node.getItem());
        result.put("index", node.getIndex());
        result.put("open", node.getOpen());
        result.put("close", node.getClose());
        result.put("separator", node.getSeparator());
        result.put("contents", serialize(node.getContents()));
    }

    private static void serializeChoose(ChooseSqlNode node, Map<String, Object> result) {
        result.put("whenNodes", node.getWhenContents().stream()
                .map(SqlNodeSerializer::serialize)
                .collect(Collectors.toList()));
        result.put("otherwise", serialize(node.getOtherwiseContents()));
    }

    private static void serializeTrim(TrimSqlNode node, Map<String, Object> result) {
        result.put("prefix", node.getPrefix());
        result.put("suffix", node.getSuffix());
        result.put("prefixesToOverride", node.getPrefixOverrides());
        result.put("suffixesToOverride", node.getSuffixOverrides());
        result.put("contents", serialize(node.getContents()));
    }

    private static void serializeSet(SetSqlNode node, Map<String, Object> result) {
        result.put("contents", serialize(node.getContents()));
    }

    private static void serializeWhere(WhereSqlNode node, Map<String, Object> result) {
        result.put("contents", serialize(node.getContents()));
    }

    private static void serializeBind(BindSqlNode node, Map<String, Object> result) {
        result.put("name", node.getName());
        result.put("expression", node.getExpression());
    }

    private static void serializeMixed(MixedSqlNode node, Map<String, Object> result) {
        result.put("children", node.getContents().stream()
                .map(SqlNodeSerializer::serialize)
                .collect(Collectors.toList()));
    }

    /**
     * 将 Map 转换为 JSON 字符串（用于调试）
     */
    public static String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 从 JSON 字符串解析为 Map（用于调试）
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
