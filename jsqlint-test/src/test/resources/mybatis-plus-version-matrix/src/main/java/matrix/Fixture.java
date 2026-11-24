package matrix;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@TableName("matrix_nodes")
class Node {
    @TableId(value = "node_key", type = IdType.AUTO) public Long id;
    @TableField("node_id") public Long nodeId;
    @TableField("display_name") public String name;
    public String value;
    @TableField(select = false) public String secret;
    @TableField(exist = false) public String temporary;

    public Long getId() { return id; }
    public Long getNodeId() { return nodeId; }
    public String getName() { return name; }
    public String getValue() { return value; }
    public String getSecret() { return secret; }
}

@TableName("matrix_logic_nodes")
class LogicNode extends Node {
    @TableLogic(value = "0", delval = "1") public Integer deleted = 0;
    public Integer getDeleted() { return deleted; }
}

interface NodeMapper extends BaseMapper<Node> {}
interface LogicNodeMapper extends BaseMapper<LogicNode> {}

/** 相同源码分别对目标版本编译；Oracle 只运行这些受控方法，不访问数据库。 */
public class Fixture {
    public static void plainSelectById(NodeMapper mapper) { mapper.selectById(42L); }
    public static void plainSelectNull(NodeMapper mapper) { mapper.selectList(null); }
    public static void plainSelectQuery(NodeMapper mapper) {
        mapper.selectList(new QueryWrapper<Node>().eq("node_id", 42L).like("display_name", "Al").orderByAsc("node_key"));
    }
    public static void plainSelectLambda(NodeMapper mapper) {
        mapper.selectList(new QueryWrapper<Node>().lambda().eq(Node::getNodeId, 42L).orderByAsc(Node::getId));
    }
    public static void plainCountNull(NodeMapper mapper) { mapper.selectCount(null); }
    public static void plainCountSelected(NodeMapper mapper) { mapper.selectCount(new QueryWrapper<Node>().select("node_key")); }
    public static void plainSelectObjs(NodeMapper mapper) { mapper.selectObjs(null); }
    public static void plainInsert(NodeMapper mapper) { mapper.insert(node()); }
    public static void plainInsertAutoId(NodeMapper mapper) {
        Node node = node(); node.id = null; mapper.insert(node);
    }
    public static void plainUpdateById(NodeMapper mapper) { mapper.updateById(node()); }
    public static void plainDeleteById(NodeMapper mapper) { mapper.deleteById(42L); }

    public static void logicSelectById(LogicNodeMapper mapper) { mapper.selectById(42L); }
    public static void logicSelectNull(LogicNodeMapper mapper) { mapper.selectList(null); }
    public static void logicSelectQuery(LogicNodeMapper mapper) {
        mapper.selectList(new QueryWrapper<LogicNode>().eq("node_id", 42L).orderByDesc("node_key"));
    }
    public static void logicSelectLambda(LogicNodeMapper mapper) {
        mapper.selectList(new QueryWrapper<LogicNode>().lambda().eq(LogicNode::getNodeId, 42L).orderByAsc(LogicNode::getId));
    }
    public static void logicCountNull(LogicNodeMapper mapper) { mapper.selectCount(null); }
    public static void logicCountSelected(LogicNodeMapper mapper) { mapper.selectCount(new QueryWrapper<LogicNode>().select("node_key")); }
    public static void logicSelectObjs(LogicNodeMapper mapper) { mapper.selectObjs(null); }
    public static void logicInsert(LogicNodeMapper mapper) { mapper.insert(logicNode()); }
    public static void logicInsertAutoId(LogicNodeMapper mapper) {
        LogicNode node = logicNode(); node.id = null; mapper.insert(node);
    }
    public static void logicUpdateById(LogicNodeMapper mapper) { mapper.updateById(logicNode()); }
    public static void logicDeleteById(LogicNodeMapper mapper) { mapper.deleteById(42L); }

    private static Node node() {
        Node node = new Node();
        node.id = 42L; node.nodeId = 7L; node.name = "Alice"; node.secret = "hidden"; node.temporary = "excluded";
        return node;
    }
    private static LogicNode logicNode() {
        LogicNode node = new LogicNode();
        node.id = 42L; node.nodeId = 7L; node.name = "Alice"; node.secret = "hidden"; node.temporary = "excluded";
        return node;
    }
}
