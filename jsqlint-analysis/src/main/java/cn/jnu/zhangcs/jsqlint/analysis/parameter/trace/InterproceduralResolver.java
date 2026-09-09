package cn.jnu.zhangcs.jsqlint.analysis.parameter.trace;

import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JReturnStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.ClassType;
import sootup.core.views.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 过程间返回值内联器：把应用内方法调用在调用点展开为调用方值模型。
 *
 * <p>覆盖的核心场景是 helper 方法拼接 SQL 后返回——
 * {@code String sql = buildDeleteSql(tenant)}。调用方解析 {@code sql} 时
 * 递归进入 {@code buildDeleteSql} 方法体，把 return 语句的操作数
 * （含形参引用）解析后按实参替换，内联为调用方的值模型。</p>
 *
 * <p>防护：递归深度上限、调用环检测（自递归/互递归方法直接放弃返回 UnknownValue）、
 * 仅处理应用类方法（库方法无方法体自然跳过）、单一 return 路径内联
 * （多 return 形态退化为候选集由调用方决定，此处仅取"可确定"路径）。</p>
 *
 * @author zhangcs
 */
final class InterproceduralResolver {

    /**
     * 内联递归深度上限。helper 链通常 1-2 层（buildSql → escape），
     * 超过该深度几乎必然是工厂/递归结构，继续展开收益趋零且有爆栈风险。
     */
    private static final int MAX_INLINE_DEPTH = 3;

    /**
     * 调用方回溯结果上限：多个调用点时取首个可解析实参即停止扫描。
     */
    private static final int MAX_CALLER_RESULTS = 1;

    private final View view;

    InterproceduralResolver(View view) {
        this.view = view;
    }

    /**
     * 尝试把应用内方法调用的返回值内联为值模型。
     *
     * @return 内联结果；不可内联（无方法体/库方法/环/超深/复杂 return 形态）返回 null，
     *         调用方退回 InvokeValue 占位
     */
    ResolvedValue tryInlineReturn(
            AbstractInvokeExpr invoke,
            java.util.function.BiFunction<sootup.core.jimple.basic.Value, Integer, ResolvedValue> argumentResolver) {

        MethodSignature callee = invoke.getMethodSignature();
        Optional<? extends SootMethod> optionalMethod = view.getMethod(callee);
        if (optionalMethod.isEmpty() || !optionalMethod.get().hasBody()) {
            return null;
        }
        SootMethod method = optionalMethod.get();

        List<ValueModel.ParameterValue> parameters = new ArrayList<>();
        for (int i = 0; i < invoke.getArgCount(); i++) {
            ResolvedValue argument = argumentResolver.apply(invoke.getArg(i), i);
            parameters.add(toParameterValue(argument.value(), i));
        }

        return inlineMethodBody(method, parameters, 0, new java.util.HashSet<>());
    }

    /**
     * 调用方实参回溯：SQL 执行方法的形参（如 queryUsers(sql, params) 的 sql）
     * 无法在本方法内解析为字符串时，沿应用类找调用点，在调用方上下文解析实参。
     *
     * <p>核心场景：{@code return queryUsers(sql.toString(), parameters)}——
     * 调用方已构造好 SQL 再传给通用执行方法。多个调用点时取首个可解析结果；
     * 回溯失败返回 null，调用方维持原有诊断路径。</p>
     *
     * @param callee         SQL 实参所在的被调方法（如 queryUsers）
     * @param parameterIndex 形参下标（SQL 文本在方法签名的位置）
     * @param visiting       回溯环检测（方法签名集合）
     * @return 实参在调用方上下文中的值模型；无调用点/全部失败返回 null
     */
    ResolvedValue resolveCallerArgument(
            SootMethod callee,
            int parameterIndex,
            java.util.Set<MethodSignature> visiting) {

        MethodSignature calleeSignature = callee.getSignature();
        if (!visiting.add(calleeSignature)) {
            return null;
        }
        try {
            ResolvedValue resolved = null;
            // 先廉价筛出真正调用 callee 的方法（不构建解析器），再仅对这些方法做实参解析，
            // 避免为全部应用类方法构建到达定义分析
            for (sootup.core.model.SootClass clazz : view.getClasses()
                    .filter(sootup.core.model.SootClass::isApplicationClass)
                    .toList()) {
                for (SootMethod callerMethod : clazz.getMethods()) {
                    if (!callerMethod.hasBody()
                            || callerMethod.getSignature().equals(calleeSignature)
                            || !containsCallTo(callerMethod, calleeSignature)) {
                        continue;
                    }
                    ResolvedValue result = resolveArgumentAtCallSite(
                            callerMethod, calleeSignature, parameterIndex, visiting);
                    if (result != null) {
                        resolved = result;
                        break;
                    }
                }
                if (resolved != null) {
                    break;
                }
            }
            return resolved;
        } finally {
            visiting.remove(calleeSignature);
        }
    }

    /**
     * 方法体内是否包含对目标签名的调用（语句文本预筛，不构建解析器）。
     */
    private boolean containsCallTo(SootMethod method, MethodSignature target) {
        String targetText = target.toString();
        for (Stmt stmt : method.getBody().getStmts()) {
            if (stmt.toString().contains(targetText)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在单个调用方方法内找对 callee 的调用点，并在调用方上下文解析指定实参。
     */
    private ResolvedValue resolveArgumentAtCallSite(
            SootMethod callerMethod,
            MethodSignature calleeSignature,
            int parameterIndex,
            java.util.Set<MethodSignature> visiting) {

        List<Stmt> stmts = callerMethod.getBody().getStmts();
        ValueModelResolver callerResolver = new ValueModelResolver(callerMethod, view);
        for (int i = 0; i < stmts.size(); i++) {
            Optional<AbstractInvokeExpr> invoke = invokeOf(stmts.get(i));
            if (invoke.isEmpty()
                    || !invoke.get().getMethodSignature().equals(calleeSignature)
                    || parameterIndex >= invoke.get().getArgCount()) {
                continue;
            }
            // 在调用方上下文解析实参（可继续触发返回值内联/嵌套回溯）
            ResolvedValue argument = callerResolver.resolveWithTrace(invoke.get().getArg(parameterIndex), i);
            if (!(argument.value() instanceof ValueModel.ParameterValue)
                    && !(argument.value() instanceof ValueModel.UnknownValue)) {
                return argument;
            }
        }
        return null;
    }

    /**
     * 提取语句中的调用表达式（与 SqlExtractionSupport.invokeOf 同构，避免跨包暴露）。
     */
    private static Optional<AbstractInvokeExpr> invokeOf(Stmt stmt) {
        if (stmt instanceof JAssignStmt assign) {
            return assign.getInvokeExpr();
        }
        if (stmt instanceof sootup.core.jimple.common.stmt.JInvokeStmt invokeStmt) {
            return invokeStmt.getInvokeExpr();
        }
        return Optional.empty();
    }

    private ResolvedValue inlineMethodBody(
            SootMethod method,
            List<ValueModel.ParameterValue> arguments,
            int depth,
            java.util.Set<MethodSignature> inlining) {

        MethodSignature signature = method.getSignature();
        if (depth >= MAX_INLINE_DEPTH || !inlining.add(signature)) {
            return null;
        }
        try {
            // 找单一 return：多于一个 return 的方法（分支返回不同值）交给调用方
            // 按到达定义收集候选，此处只内联"单一路径"形态
            sootup.core.jimple.basic.Value returnedValue = null;
            int returnCount = 0;
            for (Stmt stmt : method.getBody().getStmts()) {
                if (stmt instanceof JReturnStmt returnStmt) {
                    returnCount++;
                    if (returnCount > 1) {
                        return null;
                    }
                    returnedValue = returnStmt.getOp();
                }
            }
            if (returnedValue == null) {
                return null;
            }

            // return 操作数是中间局部（Java 9+ 拼接产物：l4 = dynamicinvoke makeConcat; return l4）
            // 时回溯其唯一定义语句，让形参替换能看到 dynamicinvoke 的实参
            if (returnedValue instanceof sootup.core.jimple.basic.Local returnLocal) {
                JAssignStmt definition = soleDefinition(method, returnLocal.getName());
                if (definition != null) {
                    returnedValue = definition.getRightOp();
                }
            }

            // 在被调用方方法体的语义下解析 return 操作数：
            // 形参引用替换为调用方实参模型，其余（常量/局部拼接）由
            // ValueModelResolver 以被调方法上下文解析
            ValueModelResolver bodyResolver = new ValueModelResolver(method, view);
            ResolvedValue resolved = bodyResolver.resolveWithArgumentSubstitution(
                    returnedValue, arguments, depth, inlining, this);
            return resolved;
        } catch (Exception ignored) {
            // 任何解析异常都退回调用方占位，不影响主流程
            return null;
        } finally {
            inlining.remove(signature);
        }
    }

    /**
     * 内联嵌套调用：被内联方法体里再调用应用内方法时，由 body resolver 回调进入。
     */
    ResolvedValue inlineNested(
            SootMethod nestedMethod,
            sootup.core.jimple.basic.Value returnedValue,
            List<ValueModel.ParameterValue> arguments,
            int depth,
            java.util.Set<MethodSignature> inlining) {

        MethodSignature signature = nestedMethod.getSignature();
        if (depth >= MAX_INLINE_DEPTH || !inlining.add(signature)) {
            return null;
        }
        try {
            ValueModelResolver bodyResolver = new ValueModelResolver(nestedMethod, view);
            return bodyResolver.resolveWithArgumentSubstitution(
                    returnedValue, arguments, depth, inlining, this);
        } catch (Exception ignored) {
            return null;
        } finally {
            inlining.remove(signature);
        }
    }

    View view() {
        return view;
    }

    /**
     * 找局部变量在该方法体内的唯一赋值语句；多于一个定义（分支赋值）时返回 null，
     * 交回标准路径按到达定义展开候选。
     */
    private JAssignStmt soleDefinition(SootMethod method, String localName) {
        JAssignStmt found = null;
        for (Stmt stmt : method.getBody().getStmts()) {
            if (stmt instanceof JAssignStmt assign
                    && assign.getLeftOp() instanceof sootup.core.jimple.basic.Local left
                    && left.getName().equals(localName)) {
                if (found != null) {
                    return null;
                }
                found = assign;
            }
        }
        return found;
    }

    private ValueModel.ParameterValue toParameterValue(ValueModel argument, int index) {
        // 内联实参本身是 ParameterValue 时保留原身份（跨方法传播可解释）；
        // 否则包装为"内联实参"标记，供展示层区分来源
        if (argument instanceof ValueModel.ParameterValue parameter) {
            return parameter;
        }
        return new ValueModel.ParameterValue(index, "arg" + index, argument.display());
    }
}
