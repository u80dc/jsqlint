package cn.jnu.zhangcs.jsqlint.analysis.node;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * CHOOSE (WHEN/OTHERWISE) 节点
 *
 * @author zhangcs
 */
@Getter
@RequiredArgsConstructor
public class ChooseSqlNode implements SqlNode {

    private final List<SqlNode> whenContents;

    private final SqlNode otherwiseContents;

    @Override
    public boolean isDynamic() {
        boolean dynamic = whenContents.stream().anyMatch(SqlNode::isDynamic);
        if (otherwiseContents != null) {
            dynamic = dynamic || otherwiseContents.isDynamic();
        }
        return dynamic;
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.CHOOSE;
    }
}