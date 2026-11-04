package org.u80dc.jsqlint.core.node;

/**
 * 不可变的通用动态 SQL 中间表示，与来源框架和表达式语言无关。
 * 动态表示需要参数或条件求值，不代表 SQL 注入风险。
 * 节点按具体类型和全部语义字段进行结构比较，子节点列表的顺序参与比较；
 * 此比较不判断 SQL 语义等价。需要区分相同结构的不同出现位置时应使用对象身份。
 *
 * @author u80dc
 */
public sealed interface SqlNode permits BindSqlNode, ChooseSqlNode, ForEachSqlNode,
        IfSqlNode, MixedSqlNode, SetSqlNode, StaticTextSqlNode, TextSqlNode,
        TrimSqlNode, WhereSqlNode, ValueSqlNode {

    /**
     * 判断是否为动态节点
     */
    boolean isDynamic();

    /**
     * 获取节点类型
     */
    SqlNodeType getType();
}
