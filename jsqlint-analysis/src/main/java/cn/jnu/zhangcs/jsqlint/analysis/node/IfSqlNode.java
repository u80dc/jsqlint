package cn.jnu.zhangcs.jsqlint.analysis.node;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * IF 条件节点
 *
 * @author zhangcs
 */
@Getter
@RequiredArgsConstructor
public class IfSqlNode implements SqlNode {

    private final String test;

    private final SqlNode contents;

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.IF;
    }
}