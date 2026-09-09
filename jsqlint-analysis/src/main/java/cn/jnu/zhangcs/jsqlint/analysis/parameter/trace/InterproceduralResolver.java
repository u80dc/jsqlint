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
