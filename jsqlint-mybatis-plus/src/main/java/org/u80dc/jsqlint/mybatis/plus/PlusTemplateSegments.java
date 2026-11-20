package org.u80dc.jsqlint.mybatis.plus;

import org.u80dc.jsqlint.core.node.*;
import java.util.*;

/** 在原 SQL 树中替换 Wrapper 片段；保留 XML 控制节点、空白和其他参数表达式。 */
final class PlusTemplateSegments {
    private final Map<String,SqlNode> replacements;
    private final Runnable checkpoint;
    private int steps;
    PlusTemplateSegments(Map<String,SqlNode> replacements,Runnable checkpoint) {this.replacements=Map.copyOf(replacements);this.checkpoint=checkpoint;}
    SqlNode replace(SqlNode node) { return replace(node,0); }
    private SqlNode replace(SqlNode node,int depth) {
        checkpoint.run();if(++steps>10000||depth>128)throw new IllegalArgumentException("Wrapper XML substitution budget exceeded");
        return switch(node) {
            case ValueSqlNode value -> value.getKind()==ValueSqlNode.Kind.RAW_TEXT?replacements.getOrDefault(value.getExpression().strip(),value):value;
            case MixedSqlNode mixed -> new MixedSqlNode(mixed.contents().stream().map(child->replace(child,depth+1)).toList(),mixed.separator());
            case IfSqlNode conditional -> new IfSqlNode(conditional.test(),replace(conditional.contents(),depth+1));
            case ChooseSqlNode choose -> new ChooseSqlNode(choose.whenContents().stream().map(child->(IfSqlNode)replace(child,depth+1)).toList(),choose.otherwiseContents()==null?null:replace(choose.otherwiseContents(),depth+1));
            case WhereSqlNode where -> new WhereSqlNode(replace(where.contents(),depth+1));
            case SetSqlNode set -> new SetSqlNode(replace(set.contents(),depth+1));
            case TrimSqlNode trim -> new TrimSqlNode(trim.prefix(),trim.suffix(),trim.prefixOverrides(),trim.suffixOverrides(),replace(trim.contents(),depth+1));
            case ForEachSqlNode loop -> new ForEachSqlNode(loop.collection(),loop.item(),loop.index(),loop.open(),loop.close(),loop.separator(),replace(loop.contents(),depth+1),loop.nullable(),loop.skipBlank());
            default -> node;
        };
    }
}
