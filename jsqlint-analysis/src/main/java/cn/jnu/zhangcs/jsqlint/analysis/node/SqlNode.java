package cn.jnu.zhangcs.jsqlint.analysis.node;

/**
 * MyBatis XML SqlNode 基类接口
 *
 * @author zhangcs
 */
public interface SqlNode {

    /**
     * 判断是否为动态节点
     */
    boolean isDynamic();

    /**
     * 获取节点类型
     */
    SqlNodeType getType();
}