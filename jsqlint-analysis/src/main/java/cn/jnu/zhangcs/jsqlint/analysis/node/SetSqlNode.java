package cn.jnu.zhangcs.jsqlint.analysis.node;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * SET 节点（UPDATE 语句中的 SET）
 *
 * @author zhangcs
 */
@Getter
@RequiredArgsConstructor
public class SetSqlNode implements SqlNode {

    private final SqlNode contents;

    @Override
    public boolean isDynamic() {
        return contents.isDynamic();
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.SET;
    }
}