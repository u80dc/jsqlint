package cn.jnu.zhangcs.jsqlint.analysis.node;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 混合内容节点（包含多个子节点）
 *
 * @author zhangcs
 */
@Getter
@RequiredArgsConstructor
public class MixedSqlNode implements SqlNode {

    private final List<SqlNode> contents;

    @Override
    public boolean isDynamic() {
        return contents.stream().anyMatch(SqlNode::isDynamic);
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.MIXED;
    }
}