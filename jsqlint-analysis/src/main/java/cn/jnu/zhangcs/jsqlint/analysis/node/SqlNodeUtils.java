package cn.jnu.zhangcs.jsqlint.analysis.node;

/**
 * SqlNode 工具类
 *
 * @author zhangcs
 */
public final class SqlNodeUtils {

    private SqlNodeUtils() {
    }

    /**
     * 统计 SqlNode 树中动态节点的数量（用于衡量 SQL 复杂度）
     *
     * <p>动态节点包括：if、forEach、choose、trim、text（含 ${} 表达式）、bind</p>
     *
     * @param node SqlNode 树根节点
     * @return 动态节点数量
     */
    public static int countDynamicNodes(SqlNode node) {
        if (node == null) {
            return 0;
        }

        int count = 0;

        if (node instanceof MixedSqlNode mixed) {
            // 容器节点，本身不计数，递归子节点
            for (SqlNode child : mixed.getContents()) {
                count += countDynamicNodes(child);
            }
        } else if (node instanceof IfSqlNode ifNode) {
            count += 1;
            count += countDynamicNodes(ifNode.getContents());
        } else if (node instanceof ForEachSqlNode forEach) {
            count += 1;
            count += countDynamicNodes(forEach.getContents());
        } else if (node instanceof ChooseSqlNode choose) {
            count += 1;
            for (SqlNode when : choose.getWhenContents()) {
                count += countDynamicNodes(when);
            }
            if (choose.getOtherwiseContents() != null) {
                count += countDynamicNodes(choose.getOtherwiseContents());
            }
        } else if (node instanceof WhereSqlNode where) {
            // where 本身不计数（是语法糖），递归子节点
            count += countDynamicNodes(where.getContents());
        } else if (node instanceof TrimSqlNode trim) {
            count += 1;
            count += countDynamicNodes(trim.getContents());
        } else if (node instanceof SetSqlNode set) {
            // set 本身不计数（是语法糖），递归子节点
            count += countDynamicNodes(set.getContents());
        } else if (node instanceof TextSqlNode) {
            // 含 ${} 表达式的文本节点
            count += 1;
        } else if (node instanceof BindSqlNode) {
            count += 1;
        }
        // StaticTextSqlNode 不是动态节点，不计数

        return count;
    }
}
