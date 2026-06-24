package cn.jnu.zhangcs.jsqlint.analysis.node;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * FOREACH 循环节点
 */
@Getter
@RequiredArgsConstructor
public class ForEachSqlNode implements SqlNode {

    private final String collection;

    private final String item;

    private final String index;

    private final String open;

    private final String close;

    private final String separator;

    private final SqlNode contents;

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.FOREACH;
    }
}