package org.u80dc.jsqlint.core.parameter;

import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.spi.*;
import org.u80dc.jsqlint.core.dataflow.CallContext;
import org.u80dc.jsqlint.core.dataflow.LocalDefinitions;
import sootup.core.jimple.common.*;
import sootup.core.jimple.common.constant.*;
import sootup.core.jimple.common.expr.*;
import sootup.core.jimple.common.ref.JParameterRef;
import sootup.core.jimple.common.stmt.*;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * 按需解析 SQL 值：控制流到达定义、参数调用来源、静态/特殊调用返回值和 Java 字符串连接。
 * 不执行目标代码。分支保留为符号选择，外部参数、动态派发和不支持的表达式保留未知值。
 * 方法到达定义查询使用有界会话缓存；跨调用的参数绑定不写入共享缓存。
 * @author u80dc
 */
public final class ValueTraceAnalyzer {
    private final JavaView view;
    private final Map<MethodSignature, List<InvocationSite>> callers;
    private final int maxDepth;
    private final Runnable checkpoint;
    private final BiConsumer<String, String> incomplete;
    // 只缓存与调用上下文无关的到达定义查询；外层按最近访问顺序限制方法数量。
    private final LocalDefinitions definitions;
    private int symbols;

    public ValueTraceAnalyzer(JavaView view, Map<MethodSignature, List<InvocationSite>> callers,
                              int maxDepth, int cacheSize, Runnable checkpoint, BiConsumer<String, String> incomplete) {
        this.view = view;
        this.callers = callers;
        this.maxDepth = maxDepth;
        this.checkpoint = checkpoint;
        this.incomplete = incomplete;
        definitions = new LocalDefinitions(cacheSize, checkpoint);
    }

    private ValueTraceAnalyzer(ValueTraceAnalyzer parent, BiConsumer<String, String> diagnostic) {
        view = parent.view; callers = parent.callers; maxDepth = parent.maxDepth;
        checkpoint = parent.checkpoint; definitions = parent.definitions; incomplete = diagnostic;
    }

    /** 单条 SQL 的恢复状态；共享定义缓存，但符号与诊断在查询间隔离。 */
    public Recovery recoverSql(SqlInput.ValueAt input, CallContext context) {
        List<String> issues = new ArrayList<>();
        var query = new ValueTraceAnalyzer(this, (code, message) -> {
            issues.add(code + ": " + message);
            incomplete.accept(code, message);
        });
        SqlNode template = query.sql(input, context);
        return new Recovery(template, issues);
    }

    public record Recovery(SqlNode template, List<String> issues) {
        public Recovery { Objects.requireNonNull(template); issues = List.copyOf(issues); }
    }

    /** 从 SQL 使用点向前追踪值，再将常量、拼接和分支统一转换为 SQL 节点。 */
    public SqlNode sql(SqlInput.ValueAt input) {
        return node(resolve(input.method(), input.statement(), input.value(), Map.of(), new HashSet<>(), 0), false);
    }

    /** 在指定调用上下文求 SQL 文本，避免不同调用方的实参混入当前结果。 */
    public SqlNode sql(SqlInput.ValueAt input, CallContext context) {
        Map<Integer, Term> bindings = Map.of();
        for (InvocationSite call : context.calls()) {
            Map<Integer, Term> arguments = new LinkedHashMap<>();
            for (int i = 0; i < call.invocation().getArgCount(); i++)
                arguments.put(i, new Deferred(call.method(), call.statement(), call.invocation().getArg(i), bindings));
            bindings = arguments;
        }
        return node(resolve(input.method(), input.statement(), input.value(), bindings, new HashSet<>(), 0), false);
    }

    /**
     * bindings 保存本次调用已解析的实参；active 仅记录当前递归路径。
     * 两者共同区分调用上下文，避免不同调用相互污染，也避免将共享子表达式误判为循环。
     */
    private Term resolve(JavaSootMethod method, Stmt use, Value value, Map<Integer, Term> bindings,
                         Set<Key> active, int depth) {
        checkpoint.run();
        if (depth >= maxDepth) {
            incomplete.accept("TRACE_LIMIT", "Trace depth exceeded at " + method.getSignature() + ": " + value);
            return new Unknown("trace_limit");
        }
        if (value instanceof StringConstant constant) return new Literal(constant.getValue());
        if (value instanceof IntConstant constant) return new Literal(constant.getValue());
        if (value instanceof LongConstant constant) return new Literal(constant.getValue());
        if (value instanceof FloatConstant constant) return new Literal(constant.getValue());
        if (value instanceof DoubleConstant constant) return new Literal(constant.getValue());
        if (value instanceof BooleanConstant constant) return new Literal(constant.getValue());
        if (value instanceof NullConstant) return new Literal(null);
        Key key = new Key(method.getSignature(), use, value, bindings);
        if (!active.add(key)) return unsupported("cyclic_value");
        try {
            if (value instanceof Local local) {
                // 按控制流寻找所有可达赋值，不能简单取源码中最近的一次赋值。
                var reaching = definitions.reaching(method, local, use);
                List<Term> values = new ArrayList<>();
                for (Stmt definition : reaching.statements()) {
                    Value right = ((AbstractDefinitionStmt) definition).getRightOp();
                    values.add(resolve(method, definition, right, bindings, active, depth + 1));
                }
                if (reaching.missing() || values.isEmpty()) values.add(new Unknown("undefined_" + local.getName()));
                return alternatives(values);
            }
            if (value instanceof JParameterRef parameter) {
                // 已知调用上下文优先；否则从共享调用者索引回溯实参，不重复扫描整个项目。
                if (bindings.containsKey(parameter.getIndex())) {
                    Term bound = bindings.get(parameter.getIndex());
                    if (bound instanceof Deferred deferred)
                        return resolve(deferred.method(), deferred.use(), deferred.value(), deferred.bindings(), active, depth + 1);
                    return bound;
                }
                List<Term> values = new ArrayList<>();
                for (InvocationSite caller : callers.getOrDefault(method.getSignature(), List.of())) {
                    if (caller.invocation().getArgCount() > parameter.getIndex())
                        values.add(resolve(caller.method(), caller.statement(),
                                caller.invocation().getArg(parameter.getIndex()), Map.of(), active, depth + 1));
                }
                // 非私有方法可能还有项目外调用，已找到的实参不能代表全部输入，需保留未知分支。
                if (!method.isPrivate() || values.isEmpty()) values.add(new Unknown("parameter_" + parameter.getIndex()));
                return alternatives(values);
            }
            if (value instanceof JCastExpr cast) {
                // 转为 String 不改变字符串内容；数值等其他类型转换可能改变值，暂不直接透传。
                if (cast.getType().toString().equals("java.lang.String"))
                    return resolve(method, use, cast.getOp(), bindings, active, depth + 1);
                return unsupported("cast");
            }
            if (value instanceof JDynamicInvokeExpr dynamic) {
                // 只解释 JDK 字符串拼接协议；其他 invokedynamic 调用不套用字符串语义。
                if (!dynamic.getBootstrapMethodSignature().getDeclClassType().toString()
                        .equals("java.lang.invoke.StringConcatFactory")) return unsupported("dynamic_invocation");
                List<Term> arguments = new ArrayList<>();
                for (int i = 0; i < dynamic.getArgCount(); i++)
                    arguments.add(stringValue(resolve(method, use, dynamic.getArg(i), bindings, active, depth + 1),
                            dynamic.getMethodSignature().getParameterTypes().get(i).toString()));
                String operation = dynamic.getBootstrapMethodSignature().getName();
                if (operation.equals("makeConcat")) return new Concat(arguments);
                if (!operation.equals("makeConcatWithConstants") || dynamic.getBootstrapArgCount() == 0
                        || !(dynamic.getBootstrapArg(0) instanceof StringConstant recipe))
                    return unsupported("concat_recipe");
                List<Term> pieces = new ArrayList<>();
                StringBuilder text = new StringBuilder();
                int argument = 0, constant = 1;
                // 拼接配方中字符 1 代表动态实参，字符 2 代表引导常量，其余文本按原样保留。
                for (char character : recipe.getValue().toCharArray()) {
                    if (character != 1 && character != 2) { text.append(character); continue; }
                    pieces.add(new Literal(text.toString()));
                    text.setLength(0);
                    if (character == 1) {
                        if (argument >= arguments.size()) return unsupported("concat_arguments");
                        pieces.add(arguments.get(argument++));
                    } else {
                        if (constant >= dynamic.getBootstrapArgCount()) return unsupported("concat_constants");
                        pieces.add(resolve(method, use, dynamic.getBootstrapArg(constant++), bindings, active, depth + 1));
                    }
                }
                pieces.add(new Literal(text.toString()));
                return new Concat(pieces);
            }
            if (value instanceof AbstractInvokeExpr invocation) {
                var signature = invocation.getMethodSignature();
                if (signature.getDeclClassType().toString().equals("java.lang.String")
                        && signature.getName().equals("concat") && invocation.getArgCount() == 1
                        && invocation instanceof AbstractInstanceInvokeExpr instance) {
                    Term base = resolve(method, use, instance.getBase(), bindings, active, depth + 1);
                    Term argument = resolve(method, use, invocation.getArg(0), bindings, active, depth + 1);
                    // String.concat 遇到 null 会抛异常，不能沿用字符串加号把 null 转成文本的规则。
                    if (base instanceof Literal receiver && receiver.value() == null
                            || argument instanceof Literal parameter && parameter.value() == null)
                        return unsupported("null_string_concat");
                    return new Concat(List.of(base, argument));
                }
                // 虚调用目标尚未完成派发分析，不能将声明方法的返回值冒充实际执行结果。
                if (!(invocation instanceof JStaticInvokeExpr || invocation instanceof JSpecialInvokeExpr))
                    return unsupported("virtual_return");
                var target = view.getMethod(signature);
                if (target.isEmpty() || !target.get().hasBody()) return unsupported("unavailable_return");
                // 实参先在调用方上下文求值，再绑定到被调用方法；不同调用不共享此绑定。
                Map<Integer, Term> arguments = new LinkedHashMap<>();
                for (int i = 0; i < invocation.getArgCount(); i++)
                    arguments.put(i, resolve(method, use, invocation.getArg(i), bindings, active, depth + 1));
                List<Term> returned = new ArrayList<>();
                for (Stmt statement : target.get().getBody().getStmts()) {
                    checkpoint.run();
                    if (statement instanceof JReturnStmt result)
                        returned.add(resolve(target.get(), statement, result.getOp(), arguments, active, depth + 1));
                }
                return returned.isEmpty() ? unsupported("missing_return") : alternatives(returned);
            }
            return unsupported("unsupported_value");
        } finally {
            active.remove(key);
        }
    }

    private Unknown unsupported(String reason) {
        // 区分“输入本来未知”和“当前实现未能分析”：后者还需将本次结果标为部分完成。
        incomplete.accept("UNSUPPORTED_VALUE", "Value retained symbolically: " + reason);
        return new Unknown(reason);
    }

    /** 按调用签名恢复字符、布尔值的字符串语义，避免输出 Jimple 中的整数表示。 */
    private Term stringValue(Term term, String type) {
        if (term instanceof Literal literal && literal.value() instanceof Number number) {
            if (type.equals("char")) return new Literal((char) number.intValue());
            if (type.equals("boolean")) return new Literal(number.intValue() != 0);
        }
        if (term instanceof Alternatives choices)
            return alternatives(choices.values().stream().map(value -> stringValue(value, type)).toList());
        return term;
    }

    /** 只合并结构相同的候选值，不取首个分支，也不在此阶段展开组合。 */
    private Term alternatives(List<Term> values) {
        List<Term> distinct = values.stream().distinct().toList();
        return distinct.size() == 1 ? distinct.getFirst() : new Alternatives(distinct);
    }

    /**
     * stringified 表示当前值处于 Java 字符串拼接内部，可以进行字符串转换。
     * SQL 根值必须是字符串，不能把 null 或数值直接伪装成 SQL 文本。
     */
    private SqlNode node(Term term, boolean stringified) {
        return switch (term) {
            case Deferred deferred -> node(resolve(deferred.method(), deferred.use(), deferred.value(), deferred.bindings(), new HashSet<>(), 0), stringified);
            case Literal literal -> !stringified && !(literal.value() instanceof String)
                    ? node(unsupported("non_string_sql"), false)
                    : new StaticTextSqlNode(String.valueOf(literal.value()));
            case Unknown unknown -> ValueSqlNode.raw(unknown.reason() + "_" + symbols++, "null");
            case Concat concat -> new MixedSqlNode(concat.parts().stream().map(part -> node(part, true)).toList());
            case Alternatives alternatives -> {
                // 选择变量只是候选分支的符号标识，不冒充已经求出的真实路径条件。
                String selector = "choice_" + symbols++;
                List<IfSqlNode> branches = new ArrayList<>();
                for (int i = 0; i < alternatives.values().size(); i++)
                    branches.add(new IfSqlNode(selector + " == " + i, node(alternatives.values().get(i), stringified)));
                yield new ChooseSqlNode(branches, null);
            }
        };
    }

    /** 追踪阶段的中间值，保留候选关系，直到输出时才转换成通用 SQL 节点。 */
    private sealed interface Term {}
    private record Literal(Object value) implements Term {}
    private record Deferred(JavaSootMethod method, Stmt use, Value value, Map<Integer, Term> bindings) implements Term {}
    private record Unknown(String reason) implements Term {}
    private record Concat(List<Term> parts) implements Term {}
    private record Alternatives(List<Term> values) implements Term {}
    private record Key(MethodSignature method, Stmt use, Value value, Map<Integer, Term> bindings) {}
}
