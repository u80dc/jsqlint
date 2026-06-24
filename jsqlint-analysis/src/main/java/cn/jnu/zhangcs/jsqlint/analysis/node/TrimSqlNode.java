package cn.jnu.zhangcs.jsqlint.analysis.node;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * TRIM 节点
 */
@Getter
@RequiredArgsConstructor
public class TrimSqlNode implements SqlNode {

    private final String prefix;

    private final String suffix;

    private final String prefixOverrides;

    private final String suffixOverrides;

    private final SqlNode contents;

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.TRIM;
    }
}