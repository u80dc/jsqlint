package cn.jnu.zhangcs.jsqlint.analysis.node;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;

/**
 * WHERE 节点
 */
@Getter
@RequiredArgsConstructor
public class WhereSqlNode implements SqlNode {

    private final SqlNode contents;

    private static final List<String> prefixList = Arrays.asList(
            "AND ", "OR ",
            "AND\n", "OR\n",
            "AND\r", "OR\r",
            "AND\t", "OR\t");

    @Override
    public boolean isDynamic() {
        return contents.isDynamic();
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.WHERE;
    }
}