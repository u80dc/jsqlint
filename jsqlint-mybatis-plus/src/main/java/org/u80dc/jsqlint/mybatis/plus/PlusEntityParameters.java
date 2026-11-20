package org.u80dc.jsqlint.mybatis.plus;

import org.u80dc.jsqlint.core.SqlDefinition;
import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.dataflow.FlowPoint.ValuePoint;
import org.u80dc.jsqlint.core.spi.*;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.mybatis.MybatisOptions;
import sootup.core.jimple.common.expr.JNewExpr;
import sootup.core.types.ClassType;
import java.util.*;

/** 将实体可读属性冻结为普通 Map 及独立属性来源，模板求值无需加载目标实体。 */
final class PlusEntityParameters {
    private PlusEntityParameters() {}

    static boolean isEntity(ValuePoint input, EntityMapping entity, DataFlowAnalysis flow) {
        if (subtype(input.value().getType().toString(), entity.entityType(), flow)) return true;
        var origins = flow.origins(input, ignored -> false);
        return origins.complete() && !origins.values().isEmpty() && origins.values().stream().allMatch(origin ->
                origin.value() instanceof JNewExpr && subtype(origin.value().getType().toString(), entity.entityType(), flow));
    }

    static SqlTraceSource resolve(ValuePoint input, EntityMapping entity, SqlDefinition definition,
                                  DataFlowAnalysis flow, MybatisOptions options) {
        var parameters = new LinkedHashMap<String, TemplateSqlInput.Parameter>();
        var inputs = new ArrayList<ValueTrace.Node>();
        var issues = new LinkedHashSet<String>(definition.issues());
        var required = requiredProperties(definition.template(), entity, flow);
        for (var field : entity.fields()) {
            flow.checkpoint();
            if (!required.contains(field.property())) continue;
            // 本阶段冻结标量属性；嵌套对象和集合仍由其来源表示未知。
            ValueTrace trace = flow.traceProperty(input, field.property(), field.type());
            String command = definition.metadata().getOrDefault("command", "");
            if ((command.equals("insert") && Set.of("INSERT", "INSERT_UPDATE").contains(field.fill()))
                    || (command.equals("update") && Set.of("UPDATE", "INSERT_UPDATE").contains(field.fill())))
                trace = new ValueTrace(trace.variable(), trace.callChain(), new ValueTrace.Node(trace.variable(),
                        ValueTrace.Kind.UNRESOLVED, "Automatic field fill value is unavailable: " + field.property(), List.of(trace.root())));
            parameters.put(field.property(), new TemplateSqlInput.Parameter(trace, field.type()));
            var at = input.snapshot(flow.program());
            var key = new ValueTrace.Variable(at.method(), at.statementIndex(), "property-name:" + field.property(), "java.lang.String", at.callContext());
            inputs.add(new ValueTrace.Node(key, ValueTrace.Kind.CONSTANT, "Entity property: " + field.property(), List.of(),
                    new ValueTrace.Computation(ValueTrace.Operation.LITERAL, field.property())));
            inputs.add(trace.root());
        }
        var variable = input.snapshot(flow.program()).withType("java.util.Map");
        var snapshot = new ValueTrace(variable, input.point().context().snapshot(),
                new ValueTrace.Node(variable, ValueTrace.Kind.EXPRESSION, "Entity property snapshot", inputs,
                        new ValueTrace.Computation(ValueTrace.Operation.MAP_LITERAL, "Entity property snapshot")));
        parameters.put("_parameter", new TemplateSqlInput.Parameter(snapshot, "java.util.Map"));
        if (definition.metadata().getOrDefault("method", "").contains(" updateById("))
            parameters.put("et", new TemplateSqlInput.Parameter(snapshot, "java.util.Map"));
        var constants = new LinkedHashMap<String, Object>(); constants.put("_databaseId", options.databaseId().orElse(null));
        return SqlTraceSource.template(new TemplateSqlInput(definition, parameters, Optional.empty(), Optional.empty(), constants), List.copyOf(issues));
    }

    /** 只恢复模板实际可能读取的属性，未使用 getter 的副作用/未知来源不能污染本次绑定。 */
    private static Set<String> requiredProperties(SqlNode root, EntityMapping entity, DataFlowAnalysis flow) {
        var required = new LinkedHashSet<String>();
        var fields = entity.fields().stream().map(EntityMapping.Field::property).toList();
        Set<SqlNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<SqlNode> pending = new ArrayDeque<>(); pending.add(root);
        int steps = 0;
        while (!pending.isEmpty()) {
            flow.checkpoint();
            if (++steps > 10000) { required.addAll(fields); break; }
            var node = pending.removeLast(); if (!seen.add(node)) continue;
            String expression = switch (node) {
                case ValueSqlNode value -> value.getExpression();
                case BindSqlNode bind -> bind.expression();
                case IfSqlNode conditional -> conditional.test();
                case ForEachSqlNode loop -> loop.collection();
                case TextSqlNode text -> text.text();
                default -> "";
            };
            for (String field : fields)
                if (java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}_$])" + java.util.regex.Pattern.quote(field) + "(?![\\p{L}\\p{N}_$])")
                        .matcher(expression).find()) required.add(field);
            // Whole-object usages need a complete snapshot; a qualified property needs only that property.
            if (expression.matches("(?s).*\\b_parameter\\b(?!\\s*[.\\[]).*")) required.addAll(fields);
            for (var child : SqlNodeGraphs.children(node)) pending.addLast(child);
        }
        return required;
    }

    private static boolean subtype(String name, String target, DataFlowAnalysis flow) {
        if (name.equals(target)) return true;
        if (!(flow.program().view().getIdentifierFactory().getType(name) instanceof ClassType type)) return false;
        Set<ClassType> visited = new HashSet<>(); Deque<ClassType> pending = new ArrayDeque<>(); pending.add(type);
        while (!pending.isEmpty()) {
            flow.checkpoint(); var current = pending.removeFirst(); if (!visited.add(current)) continue;
            if (current.toString().equals(target)) return true;
            flow.program().view().getClass(current).ifPresent(clazz -> {
                clazz.getSuperclass().ifPresent(pending::add); pending.addAll(clazz.getInterfaces());
            });
        }
        return false;
    }
}
