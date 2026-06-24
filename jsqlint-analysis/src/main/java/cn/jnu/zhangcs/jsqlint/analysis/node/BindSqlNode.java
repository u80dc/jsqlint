package cn.jnu.zhangcs.jsqlint.analysis.node;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * BIND节点，对应变量声明`<bind></bind>`标签
 *
 * @author zhangcs
 */
@Getter
@RequiredArgsConstructor
public class BindSqlNode implements SqlNode {

    private final String name;

    private final String expression;

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    public SqlNodeType getType() {
        return SqlNodeType.VAR_DECL;
    }
}