package cn.jnu.zhangcs.jsqlint.server.util;

import cn.jnu.zhangcs.jsqlint.analysis.node.BindSqlNode;
import cn.jnu.zhangcs.jsqlint.analysis.node.ChooseSqlNode;
import cn.jnu.zhangcs.jsqlint.analysis.node.ForEachSqlNode;
import cn.jnu.zhangcs.jsqlint.analysis.node.IfSqlNode;
import cn.jnu.zhangcs.jsqlint.analysis.node.MixedSqlNode;
import cn.jnu.zhangcs.jsqlint.analysis.node.SetSqlNode;
import cn.jnu.zhangcs.jsqlint.analysis.node.StaticTextSqlNode;
import cn.jnu.zhangcs.jsqlint.analysis.node.TextSqlNode;
import cn.jnu.zhangcs.jsqlint.analysis.node.TrimSqlNode;
import cn.jnu.zhangcs.jsqlint.analysis.node.WhereSqlNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SqlNodeSerializer} 单元测试。
 *
 * @author zhangcs
 */
class SqlNodeSerializerTest {

    @Test
    void serialize_nullNode_returnsNull() {
        assertThat(SqlNodeSerializer.serialize(null)).isNull();
    }

    @Test
    void serialize_staticTextNode_mapsFields() {
        Map<String, Object> map = SqlNodeSerializer.serialize(new StaticTextSqlNode("SELECT * FROM t_user"));

        assertThat(map.get("type")).isEqualTo("STATIC_TEXT");
        assertThat(map.get("isDynamic")).isEqualTo(false);
        assertThat(map.get("text")).isEqualTo("SELECT * FROM t_user");
    }

    @Test
    void serialize_dynamicTextNode_mapsFields() {
        Map<String, Object> map = SqlNodeSerializer.serialize(new TextSqlNode("name = #{name}"));

        assertThat(map.get("type")).isEqualTo("TEXT");
        assertThat(map.get("isDynamic")).isEqualTo(true);
        assertThat(map.get("text")).isEqualTo("name = #{name}");
    }

    @Test
    void serialize_ifNode_recursivelySerializesContents() {
        IfSqlNode node = new IfSqlNode("name != null", new StaticTextSqlNode("AND name = #{name}"));
        Map<String, Object> map = SqlNodeSerializer.serialize(node);

        assertThat(map.get("type")).isEqualTo("IF");
        assertThat(map.get("test")).isEqualTo("name != null");

        @SuppressWarnings("unchecked")
        Map<String, Object> contents = (Map<String, Object>) map.get("contents");
        assertThat(contents.get("type")).isEqualTo("STATIC_TEXT");
        assertThat(contents.get("text")).isEqualTo("AND name = #{name}");
    }

    @Test
    void serialize_forEachNode_includesAllAttributes() {
        ForEachSqlNode node = new ForEachSqlNode(
                "ids", "id", "idx", "(", ")", ",",
                new StaticTextSqlNode("#{id}"));
        Map<String, Object> map = SqlNodeSerializer.serialize(node);

        assertThat(map.get("type")).isEqualTo("FOREACH");
        assertThat(map.get("collection")).isEqualTo("ids");
        assertThat(map.get("item")).isEqualTo("id");
        assertThat(map.get("index")).isEqualTo("idx");
        assertThat(map.get("open")).isEqualTo("(");
        assertThat(map.get("close")).isEqualTo(")");
        assertThat(map.get("separator")).isEqualTo(",");
        assertThat(map).containsKey("contents");
    }

    @Test
    void serialize_chooseNode_includesWhenAndOtherwise() {
        ChooseSqlNode node = new ChooseSqlNode(
                List.of(new IfSqlNode("a != null", new StaticTextSqlNode("a"))),
                new StaticTextSqlNode("b"));
        Map<String, Object> map = SqlNodeSerializer.serialize(node);

        assertThat(map.get("type")).isEqualTo("CHOOSE");
        assertThat(map).containsKey("whenNodes");
        assertThat(map).containsKey("otherwise");
    }

    @Test
    void serialize_trimNode_includesPrefixConfig() {
        TrimSqlNode node = new TrimSqlNode(
                "WHERE", null, "AND |OR ", null,
                new StaticTextSqlNode("id = #{id}"));
        Map<String, Object> map = SqlNodeSerializer.serialize(node);

        assertThat(map.get("type")).isEqualTo("TRIM");
        assertThat(map.get("prefix")).isEqualTo("WHERE");
        assertThat(map.get("suffix")).isNull();
        assertThat(map.get("prefixesToOverride")).isEqualTo("AND |OR ");
        assertThat(map.get("suffixesToOverride")).isNull();
        assertThat(map).containsKey("contents");
    }

    @Test
    void serialize_setAndWhereNodes_includeContents() {
        Map<String, Object> setMap = SqlNodeSerializer.serialize(
                new SetSqlNode(new StaticTextSqlNode("name = #{name}")));
        assertThat(setMap.get("type")).isEqualTo("SET");
        assertThat(setMap).containsKey("contents");

        Map<String, Object> whereMap = SqlNodeSerializer.serialize(
                new WhereSqlNode(new StaticTextSqlNode("id = #{id}")));
        assertThat(whereMap.get("type")).isEqualTo("WHERE");
        assertThat(whereMap).containsKey("contents");
    }

    @Test
    void serialize_bindNode_nameAndExpressionAreNull() {
        Map<String, Object> map = SqlNodeSerializer.serialize(
                new BindSqlNode("pattern", "'%' + keyword + '%'"));

        // BindSqlNode.getType() 返回 VAR_DECL，而序列化器 switch 无该分支，仅输出通用字段
        assertThat(map.get("type")).isEqualTo("VAR_DECL");
        assertThat(map.get("isDynamic")).isEqualTo(true);
        assertThat(map.get("name")).isNull();
        assertThat(map.get("expression")).isNull();
    }

    @Test
    void serialize_mixedNode_includesChildren() {
        MixedSqlNode node = new MixedSqlNode(List.of(
                new StaticTextSqlNode("SELECT * FROM t "),
                new IfSqlNode("id != null", new StaticTextSqlNode("WHERE id = #{id}"))));
        Map<String, Object> map = SqlNodeSerializer.serialize(node);

        assertThat(map.get("type")).isEqualTo("MIXED");
        assertThat(map.get("isDynamic")).isEqualTo(true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) map.get("children");
        assertThat(children).hasSize(2);
        assertThat(children.get(0).get("type")).isEqualTo("STATIC_TEXT");
        assertThat(children.get(1).get("type")).isEqualTo("IF");
    }

    @Test
    void toJson_roundTripsWithFromJson() {
        Map<String, Object> map = SqlNodeSerializer.serialize(new StaticTextSqlNode("text"));

        String json = SqlNodeSerializer.toJson(map);
        assertThat(json).contains("\"type\"").contains("\"STATIC_TEXT\"");

        Map<String, Object> parsed = SqlNodeSerializer.fromJson(json);
        assertThat(parsed.get("type")).isEqualTo("STATIC_TEXT");
        assertThat(parsed.get("text")).isEqualTo("text");
    }

    @Test
    void fromJson_invalidInput_returnsEmptyMap() {
        assertThat(SqlNodeSerializer.fromJson("not a json")).isEmpty();
    }

    @Test
    void fromJson_nullInput_returnsEmptyMap() {
        assertThat(SqlNodeSerializer.fromJson(null)).isEmpty();
    }
}
