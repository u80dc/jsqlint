package cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider;

import sootup.core.jimple.basic.Immediate;
import sootup.core.jimple.basic.LValue;
import sootup.core.jimple.basic.Local;
import sootup.core.jimple.basic.Value;
import sootup.core.jimple.common.constant.IntConstant;
import sootup.core.jimple.common.constant.NullConstant;
import sootup.core.jimple.common.constant.StringConstant;
import sootup.core.jimple.common.expr.*;
import sootup.core.jimple.common.ref.*;
import sootup.core.jimple.common.stmt.*;
import sootup.core.model.Body;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.core.views.View;
import sootup.java.core.AnnotationUsage;
import sootup.java.core.jimple.basic.JavaLocal;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * SQL分析器
 * <p>基于 SootUp 的 Jimple IR，推断提供者方法返回的字符串内容</p>
 *
 * @author zhangcs
 */
public final class ProviderSqlAnalyzer {

    private static final Set<String> SQL_METHODS = Set.of(
            "SELECT", "SELECT_DISTINCT", "FROM", "JOIN", "INNER_JOIN", "LEFT_JOIN",
            "RIGHT_JOIN", "FULL_JOIN", "LEFT_OUTER_JOIN", "RIGHT_OUTER_JOIN",
            "OUTER_JOIN", "WHERE", "ORDER_BY", "GROUP_BY", "HAVING", "LIMIT",
            "SET", "INSERT_INTO", "UPDATE", "DELETE_FROM", "VALUES", "INTO_COLUMNS",
            "INTO_VALUES", "ADD_ROW", "OFFSET", "FETCH_FIRST_ROWS_ONLY", "OFFSET_ROWS",
            "AND", "OR"
    );

    private final View view;

    private final int maxCallDepth;

    /**
     * 创建使用默认最大调用深度（8）的实例。
     *
     * @param view SootUp 类视图
     */
    public ProviderSqlAnalyzer(View view) {
        this(view, 8);
    }

    /**
     * 创建指定最大调用深度的实例。
     *
     * @param view         SootUp 类视图
     * @param maxCallDepth 方法执行的最大递归深度
     */
    public ProviderSqlAnalyzer(View view, int maxCallDepth) {
        this.view = Objects.requireNonNull(view, "view");
        this.maxCallDepth = maxCallDepth;
    }

    /**
     * 对目标 provider 方法做抽象执行，推断其返回的 SQL 脚本。
     *
     * @param targetMethod 目标方法签名
     * @return 推断结果，包含 SQL 脚本与诊断信息
     */
    public ProviderSqlStatement analyze(MethodSignature targetMethod) {
        List<String> diagnostics = new ArrayList<>();
        Optional<? extends SootMethod> method = view.getMethod(targetMethod);
        if (method.isEmpty()) {
            diagnostics.add("Method not found: " + targetMethod);
            return new ProviderSqlStatement(targetMethod, null, diagnostics);
        }
        if (!method.get().hasBody()) {
            diagnostics.add("Method has no body: " + targetMethod);
            return new ProviderSqlStatement(targetMethod, null, diagnostics);
        }

        SqlValue returned = executeMethod(method.get(), defaultArguments(method.get()), null, diagnostics, 0);
        String inferred = renderReturnValue(returned);
        return new ProviderSqlStatement(targetMethod, inferred, diagnostics);
    }

    /**
     * 仅返回推断出的 SQL 脚本文本，不携带诊断信息。
     *
     * @param targetMethod 目标方法签名
     * @return SQL 脚本，推断失败时可能为 null
     */
    public String inferScript(MethodSignature targetMethod) {
        return analyze(targetMethod).sql();
    }

    private Optional<SqlValue> tryInferStringBuilderLoopMethod(SootMethod method, List<SqlValue> arguments) {
        Body body = method.getBody();
        List<Stmt> stmts = body.getStmts();
        if (!containsBackwardBranch(body, stmts)) {
            return Optional.empty();
        }

        Map<String, SqlValue> locals = new LinkedHashMap<>();
        Set<String> builderAliases = new LinkedHashSet<>();
        ObjectValue builderObject = null;
        List<SqlValue> appendedValues = new ArrayList<>();

        for (Stmt stmt : stmts) {
            if (stmt instanceof JIdentityStmt identity && identity.getRightOp() instanceof JParameterRef parameterRef) {
                SqlValue value = parameterRef.getIndex() < arguments.size()
                        ? arguments.get(parameterRef.getIndex())
                        : new ParameterValue(parameterName(identity.getLeftOp().getName(), parameterRef.getIndex()), parameterRef.getIndex());
                locals.put(identity.getLeftOp().getName(), value);
                continue;
            }

            if (stmt instanceof JAssignStmt assign && assign.getLeftOp() instanceof Local left) {
                if (assign.getRightOp() instanceof JNewExpr newExpr && isTextBuilderType(newExpr.getType().toString())) {
                    builderObject = ObjectValue.of(newExpr.getType().toString());
                    builderAliases.add(left.getName());
                    locals.put(left.getName(), builderObject);
                } else {
                    SqlValue value = scanValue(assign.getRightOp(), locals);
                    locals.put(left.getName(), value);
                    if (value == builderObject && builderObject != null) {
                        builderAliases.add(left.getName());
                    }
                }
            }

            Optional<AbstractInvokeExpr> invoke = invokeOf(stmt);
            if (invoke.isEmpty() || !(invoke.get() instanceof AbstractInstanceInvokeExpr instanceInvoke)) {
                continue;
            }
            if (!isBuilderReference(instanceInvoke.getBase(), builderObject, locals, builderAliases)) {
                continue;
            }

            String methodName = instanceInvoke.getMethodSignature().getName();
            if ("<init>".equals(methodName) || "append".equals(methodName)) {
                for (Immediate arg : instanceInvoke.getArgs()) {
                    appendedValues.add(scanValue(arg, locals));
                }
                if (stmt instanceof JAssignStmt assign && assign.getLeftOp() instanceof Local left) {
                    locals.put(left.getName(), builderObject);
                    builderAliases.add(left.getName());
                }
            }
        }

        return buildCollectionLoopValue(appendedValues);
    }

    private boolean isBuilderReference(
            Value value,
            ObjectValue builderObject,
            Map<String, SqlValue> locals,
            Set<String> builderAliases) {

        if (builderObject == null || !(value instanceof Local local)) {
            return false;
        }
        if (builderAliases.contains(local.getName())) {
            return true;
        }
        return locals.get(local.getName()) == builderObject;
    }

    private boolean containsBackwardBranch(Body body, List<Stmt> stmts) {
        for (int i = 0; i < stmts.size(); i++) {
            Stmt stmt = stmts.get(i);
            int target = -1;
            if (stmt instanceof JIfStmt ifStmt) {
                target = firstTargetIndex(body, stmts, ifStmt);
            } else if (stmt instanceof JGotoStmt gotoStmt) {
                target = firstTargetIndex(body, stmts, gotoStmt);
            }
            if (target >= 0 && target <= i) {
                return true;
            }
        }
        return false;
    }

    private Optional<AbstractInvokeExpr> invokeOf(Stmt stmt) {
        if (stmt instanceof JAssignStmt assign) {
            return assign.getInvokeExpr();
        }
        if (stmt instanceof JInvokeStmt invokeStmt) {
            return invokeStmt.getInvokeExpr();
        }
        return Optional.empty();
    }

    private SqlValue scanValue(Value value, Map<String, SqlValue> locals) {
        if (value instanceof Local local) {
            return locals.getOrDefault(local.getName(), new PlaceholderValue(cleanName(local.getName())));
        }
        if (value instanceof StringConstant stringConstant) {
            return new FragmentValue(new TextFragment(stringConstant.getValue()));
        }
        if (value instanceof IntConstant intConstant) {
            return new FragmentValue(new TextFragment(Integer.toString(intConstant.getValue())));
        }
        if (value instanceof JCastExpr castExpr) {
            return scanValue(castExpr.getOp(), locals);
        }
        if (value instanceof JAddExpr addExpr) {
            return concatValues(scanValue(addExpr.getOp1(), locals), scanValue(addExpr.getOp2(), locals));
        }
        if (value instanceof AbstractInstanceInvokeExpr instanceInvoke) {
            String methodName = instanceInvoke.getMethodSignature().getName();
            SqlValue base = scanValue(instanceInvoke.getBase(), locals);
            if ("get".equals(methodName) && (base instanceof ParameterValue || base instanceof PlaceholderValue)) {
                return new CollectionItemValue(conditionOperand(base));
            }
            if (isPrimitiveWrapperValueMethod(methodName)) {
                return base;
            }
        }
        if (value instanceof AbstractInvokeExpr invoke
                && "valueOf".equals(invoke.getMethodSignature().getName())
                && invoke.getArgCount() > 0) {
            return scanValue(invoke.getArg(0), locals);
        }
        return new FragmentValue(new TextFragment(value.toString()));
    }

    private Optional<SqlValue> buildCollectionLoopValue(List<SqlValue> appendedValues) {
        int itemIndex = -1;
        CollectionItemValue itemValue = null;
        for (int i = 0; i < appendedValues.size(); i++) {
            if (appendedValues.get(i) instanceof CollectionItemValue candidate) {
                itemIndex = i;
                itemValue = candidate;
                break;
            }
        }
        if (itemIndex < 0) {
            return Optional.empty();
        }

        List<SqlFragment> before = appendedValues.subList(0, itemIndex).stream()
                .map(this::toFragment)
                .collect(Collectors.toCollection(ArrayList::new));
        List<SqlFragment> after = appendedValues.subList(itemIndex + 1, appendedValues.size()).stream()
                .map(this::toFragment)
                .collect(Collectors.toCollection(ArrayList::new));

        SqlFragment separator = new TextFragment(", ");
        SqlFragment itemPrefix = TextFragment.EMPTY;
        List<SqlFragment> leading = new ArrayList<>(before);
        if (!leading.isEmpty() && isText(leading.getLast(), "'")) {
            itemPrefix = leading.removeLast();
        }
        if (!leading.isEmpty() && renderInline(leading.getLast()).contains(",")) {
            separator = leading.removeLast();
        }

        SqlFragment itemSuffix = TextFragment.EMPTY;
        List<SqlFragment> trailing = new ArrayList<>(after);
        if (!trailing.isEmpty() && isText(trailing.getFirst(), "'")) {
            itemSuffix = trailing.removeFirst();
        }

        List<SqlFragment> itemBody = new ArrayList<>();
        if (!isEmptyText(itemPrefix)) {
            itemBody.add(itemPrefix);
        }
        itemBody.add(new TextFragment("#{item}"));
        if (!isEmptyText(itemSuffix)) {
            itemBody.add(itemSuffix);
        }

        List<SqlFragment> result = new ArrayList<>(leading);
        result.add(new ForEachFragment(itemValue.collection, "item", renderInline(separator), new ConcatFragment(itemBody)));
        result.addAll(trailing);
        return Optional.of(new FragmentValue(new ConcatFragment(result)));
    }

    private boolean isText(SqlFragment fragment, String expected) {
        return fragment instanceof TextFragment(String text) && expected.equals(text);
    }

    private boolean isEmptyText(SqlFragment fragment) {
        return fragment instanceof TextFragment(String text) && text.isEmpty();
    }

    private Optional<String> parameterAnnotationName(SootMethod method, Local local, int index) {
        Optional<String> localAnnotation = parameterAnnotationName(local);
        return localAnnotation.isPresent() ? localAnnotation : reflectedParameterAnnotationName(method, index);
    }

    private Optional<String> parameterAnnotationName(Local local) {
        if (!(local instanceof JavaLocal javaLocal)) {
            return Optional.empty();
        }
        for (AnnotationUsage annotation : javaLocal.getAnnotations()) {
            if (!"org.apache.ibatis.annotations.Param".equals(annotation.getAnnotation().getFullyQualifiedName())) {
                continue;
            }
            return annotationString(annotation.getValues().get("value"));
        }
        return Optional.empty();
    }

    private Optional<String> reflectedParameterAnnotationName(SootMethod method, int index) {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> owner = Class.forName(method.getDeclClassType().getFullyQualifiedName(), false, loader);
            for (java.lang.reflect.Method candidate : owner.getDeclaredMethods()) {
                if (!matches(candidate, method) || index >= candidate.getParameterCount()) {
                    continue;
                }
                for (Annotation annotation : candidate.getParameterAnnotations()[index]) {
                    if (!"org.apache.ibatis.annotations.Param".equals(annotation.annotationType().getName())) {
                        continue;
                    }
                    Object value = annotation.annotationType().getMethod("value").invoke(annotation);
                    return annotationString(value);
                }
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private boolean matches(java.lang.reflect.Method candidate, SootMethod method) {
        if (!candidate.getName().equals(method.getName())
                || candidate.getParameterCount() != method.getParameterCount()) {
            return false;
        }
        for (int i = 0; i < candidate.getParameterCount(); i++) {
            if (!candidate.getParameterTypes()[i].getTypeName().equals(method.getParameterType(i).toString())) {
                return false;
            }
        }
        return true;
    }

    private Optional<String> annotationString(Object value) {
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return Optional.of(stringValue);
        }
        if (value instanceof StringConstant stringConstant && !stringConstant.getValue().isBlank()) {
            return Optional.of(stringConstant.getValue());
        }
        return Optional.empty();
    }

    private List<SqlValue> defaultArguments(SootMethod method) {
        List<SqlValue> args = new ArrayList<>();
        Body body = method.getBody();
        MethodSignature signature = method.getSignature();
        for (int i = 0; i < signature.getParameterCount(); i++) {
            String name = "param" + i;
            try {
                Local local = body.getParameterLocal(i);
                Optional<String> annotatedName = parameterAnnotationName(method, local, i);
                if (annotatedName.isPresent()) {
                    name = annotatedName.get();
                } else if (local != null && !isSyntheticLocalName(local.getName())) {
                    name = cleanName(local.getName());
                }
            } catch (RuntimeException ignored) {
            }
            args.add(new ParameterValue(name, i));
        }
        return args;
    }

    private SqlValue executeMethod(
            SootMethod method,
            List<SqlValue> arguments,
            ObjectValue thisObject,
            List<String> diagnostics,
            int depth) {

        // 每次方法执行拥有独立状态，嵌套的 provider 构造器不会把局部变量
        // 泄漏给调用方，但仍共享目标 SQL 构建器对象。
        if (depth > maxCallDepth || !method.hasBody()) {
            return UnknownValue.INSTANCE;
        }
        Optional<SqlValue> loopValue = tryInferStringBuilderLoopMethod(method, arguments);
        if (loopValue.isPresent()) {
            return loopValue.get();
        }
        ExecutionState state = new ExecutionState(view, diagnostics);
        state.argumentValues.addAll(arguments);
        state.thisObject = thisObject;
        if (thisObject != null) {
            state.builders.put(thisObject.builder.id, thisObject.builder);
            state.textBuilders.put(thisObject.textBuilder.id, thisObject.textBuilder);
        }

        Body body = method.getBody();
        ExecutionResult result = executeRange(body, body.getStmts(), 0, body.getStmts().size(), state, depth);
        if (result.returned) {
            return result.value;
        }
        if (state.syntheticReturn != null) {
            return state.syntheticReturn;
        }
        return UnknownValue.INSTANCE;
    }

    private ExecutionResult executeRange(
            Body body,
            List<Stmt> stmts,
            int start,
            int end,
            ExecutionState state,
            int depth) {

        // 按源码顺序遍历 Jimple。结构化的 if/else 块作为整体处理；
        // 不支持的后向分支直接跳过，而不当作真实循环。
        int i = start;
        while (i < end) {
            Stmt stmt = stmts.get(i);
            if (stmt instanceof JIfStmt ifStmt) {
                BranchPlan branch = buildBranchPlan(body, stmts, i, end, ifStmt, state, depth);
                if (branch != null) {
                    applyBranch(body, stmts, branch, state, depth);
                    i = branch.joinIndex;
                    continue;
                }
                i++;
                continue;
            }
            if (stmt instanceof JGotoStmt gotoStmt) {
                int targetIndex = firstTargetIndex(body, stmts, gotoStmt);
                if (targetIndex > i && targetIndex < end) {
                    i = targetIndex;
                } else {
                    i++;
                }
                continue;
            }
            if (stmt instanceof JReturnStmt returnStmt) {
                return new ExecutionResult(true, evaluate(returnStmt.getOp(), state, depth));
            }

            executeStatement(stmt, state, depth);
            i++;
        }
        return ExecutionResult.notReturned();
    }

    private BranchPlan buildBranchPlan(
            Body body,
            List<Stmt> stmts,
            int ifIndex,
            int end,
            JIfStmt firstIf,
            ExecutionState state,
            int depth) {

        // javac 会把 "a != null && !a.isEmpty()" 这类复合条件降级为一串
        // 跳转到同一 else 目标的卫语句。这里把这些卫语句折叠为一个
        // MyBatis <when test="..."> 表达式。
        int targetIndex = firstTargetIndex(body, stmts, firstIf);
        if (targetIndex <= ifIndex || targetIndex > end) {
            state.diagnostics.add("Loop or unsupported backward branch skipped at statement " + ifIndex);
            return null;
        }

        List<Integer> guardIndexes = new ArrayList<>();
        for (int i = ifIndex; i < targetIndex; i++) {
            Stmt stmt = stmts.get(i);
            if (stmt instanceof JIfStmt guard && firstTargetIndex(body, stmts, guard) == targetIndex) {
                guardIndexes.add(i);
            }
        }
        if (guardIndexes.isEmpty()) {
            return null;
        }

        int lastGuard = guardIndexes.get(guardIndexes.size() - 1);
        ExecutionState guardState = state.fork();
        List<String> tests = new ArrayList<>();
        for (int i = ifIndex; i <= lastGuard; i++) {
            Stmt stmt = stmts.get(i);
            if (stmt instanceof JIfStmt guard && guardIndexes.contains(i)) {
                tests.add(renderCondition(guard.getCondition(), false, guardState, depth));
            } else {
                executeStatement(stmt, guardState, depth);
            }
        }

        int thenStart = lastGuard + 1;
        int thenEnd = targetIndex;
        int elseStart = targetIndex;
        int elseEnd = targetIndex;
        int joinIndex = targetIndex;
        boolean hasElse = false;

        if (thenEnd > thenStart && stmts.get(thenEnd - 1) instanceof JGotoStmt gotoStmt) {
            int gotoTarget = firstTargetIndex(body, stmts, gotoStmt);
            if (gotoTarget > targetIndex && gotoTarget <= end) {
                hasElse = true;
                thenEnd--;
                elseEnd = gotoTarget;
                joinIndex = gotoTarget;
            }
        }

        String test = tests.stream()
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(" and "));
        if (test.isBlank()) {
            test = firstIf.getCondition().toString();
        }
        return new BranchPlan(test, thenStart, thenEnd, elseStart, elseEnd, joinIndex, hasElse);
    }

    private void applyBranch(
            Body body,
            List<Stmt> stmts,
            BranchPlan branch,
            ExecutionState state,
            int depth) {

        // 复制当前执行状态，分别执行两个分支，再合并各分支新增的
        // SQL 子句与普通 StringBuilder 片段。
        Map<String, Integer> baseSizes = state.builderSizes();
        Map<String, Integer> textBaseSizes = state.textBuilderSizes();
        ExecutionState thenState = state.fork();
        ExecutionResult thenResult = executeRange(body, stmts, branch.thenStart, branch.thenEnd, thenState, depth);

        ExecutionState elseState = state.fork();
        ExecutionResult elseResult = branch.hasElse
                ? executeRange(body, stmts, branch.elseStart, branch.elseEnd, elseState, depth)
                : ExecutionResult.notReturned();

        if (thenResult.returned && (!branch.hasElse || elseResult.returned)) {
            SqlFragment whenValue = toFragment(thenResult.value);
            SqlFragment otherwiseValue = branch.hasElse ? toFragment(elseResult.value) : TextFragment.EMPTY;
            state.syntheticReturn = new FragmentValue(new ChooseFragment(branch.test, List.of(whenValue), List.of(otherwiseValue)));
        }

        for (Map.Entry<String, Integer> entry : baseSizes.entrySet()) {
            SqlBuilder target = state.builders.get(entry.getKey());
            SqlBuilder thenBuilder = thenState.builders.get(entry.getKey());
            SqlBuilder elseBuilder = elseState.builders.get(entry.getKey());
            if (target == null || thenBuilder == null) {
                continue;
            }
            List<SqlClause> thenAdded = thenBuilder.clausesAfter(entry.getValue());
            List<SqlClause> elseAdded = elseBuilder == null ? List.of() : elseBuilder.clausesAfter(entry.getValue());
            mergeBranchClauses(target, branch.test, branch.hasElse, thenAdded, elseAdded);
        }

        for (Map.Entry<String, Integer> entry : textBaseSizes.entrySet()) {
            TextBuilder target = state.textBuilders.get(entry.getKey());
            TextBuilder thenBuilder = thenState.textBuilders.get(entry.getKey());
            TextBuilder elseBuilder = elseState.textBuilders.get(entry.getKey());
            if (target == null || thenBuilder == null) {
                continue;
            }
            List<SqlFragment> thenAdded = thenBuilder.fragmentsAfter(entry.getValue());
            List<SqlFragment> elseAdded = elseBuilder == null ? List.of() : elseBuilder.fragmentsAfter(entry.getValue());
            mergeBranchFragments(target, branch.test, branch.hasElse, thenAdded, elseAdded);
        }
    }

    private void mergeBranchFragments(
            TextBuilder target,
            String test,
            boolean hasElse,
            List<SqlFragment> thenAdded,
            List<SqlFragment> elseAdded) {

        if (thenAdded.isEmpty() && elseAdded.isEmpty()) {
            return;
        }
        SqlFragment dynamic = hasElse
                ? new ChooseFragment(test, concatAsSingleFragment(thenAdded), concatAsSingleFragment(elseAdded))
                : new IfFragment(test, concatAsSingleFragment(thenAdded));
        target.add(dynamic);
    }

    private List<SqlFragment> concatAsSingleFragment(List<SqlFragment> fragments) {
        if (fragments.isEmpty()) {
            return List.of();
        }
        return List.of(new ConcatFragment(new ArrayList<>(fragments)));
    }

    private void mergeBranchClauses(
            SqlBuilder target,
            String test,
            boolean hasElse,
            List<SqlClause> thenAdded,
            List<SqlClause> elseAdded) {

        // 当两个分支修改同一类 SQL 子句时，把动态标签放在该子句的确切位置；
        // 子句类型混杂时回退为原始子句渲染。
        if (thenAdded.isEmpty() && elseAdded.isEmpty()) {
            return;
        }

        if (containsKind(thenAdded, ClauseKind.INSERT_VALUES)
                && (!hasElse || containsKind(elseAdded, ClauseKind.INSERT_VALUES))) {
            List<SqlFragment> thenValues = fragmentsOfKind(thenAdded, ClauseKind.INSERT_VALUES);
            List<SqlFragment> elseValues = hasElse
                    ? fragmentsOfKind(elseAdded, ClauseKind.INSERT_VALUES)
                    : List.of(new TextFragment("null"));
            target.add(new SqlClause(ClauseKind.INSERT_VALUES, new ChooseFragment(test, thenValues, elseValues)));
            return;
        }

        Set<ClauseKind> kinds = new LinkedHashSet<>();
        thenAdded.forEach(c -> kinds.add(c.kind));
        elseAdded.forEach(c -> kinds.add(c.kind));

        if (kinds.size() == 1) {
            ClauseKind kind = kinds.iterator().next();
            List<SqlFragment> thenFragments = fragmentsOfKind(thenAdded, kind);
            List<SqlFragment> elseFragments = fragmentsOfKind(elseAdded, kind);
            SqlFragment dynamic = hasElse
                    ? new ChooseFragment(test, thenFragments, elseFragments)
                    : new IfFragment(test, thenFragments);
            target.add(new SqlClause(kind, dynamic));
            return;
        }

        SqlFragment dynamic = hasElse
                ? new ChooseFragment(test, List.of(new RawClausesFragment(thenAdded)), List.of(new RawClausesFragment(elseAdded)))
                : new IfFragment(test, List.of(new RawClausesFragment(thenAdded)));
        target.add(new SqlClause(ClauseKind.RAW, dynamic));
    }

    private boolean containsKind(List<SqlClause> clauses, ClauseKind kind) {
        return clauses.stream().anyMatch(clause -> clause.kind == kind);
    }

    private List<SqlFragment> fragmentsOfKind(List<SqlClause> clauses, ClauseKind kind) {
        return clauses.stream()
                .filter(c -> c.kind == kind)
                .map(c -> c.fragment)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private void executeStatement(Stmt stmt, ExecutionState state, int depth) {
        if (stmt instanceof JIdentityStmt identity) {
            // identity 语句在调试信息可用时，把 this 与方法参数绑定到
            // 真实的局部变量名。
            String local = identity.getLeftOp().getName();
            Value right = identity.getRightOp();
            if (right instanceof JParameterRef parameterRef) {
                SqlValue value = parameterRef.getIndex() < state.argumentValues.size()
                        ? state.argumentValues.get(parameterRef.getIndex())
                        : new ParameterValue(parameterName(local, parameterRef.getIndex()), parameterRef.getIndex());
                state.locals.put(local, value);
            } else if (right instanceof JThisRef) {
                ObjectValue thisValue = state.thisObject != null
                        ? state.thisObject
                        : ObjectValue.of(right.getType().toString());
                state.locals.put(local, thisValue);
                state.builders.put(thisValue.builder.id, thisValue.builder);
                state.textBuilders.put(thisValue.textBuilder.id, thisValue.textBuilder);
            }
            return;
        }

        if (stmt instanceof JAssignStmt assign) {
            LValue left = assign.getLeftOp();
            Value right = assign.getRightOp();

            if (left instanceof JArrayRef arrayRef) {
                updateArray(arrayRef, right, state, depth);
                return;
            }
            if (left instanceof JInstanceFieldRef fieldRef) {
                state.fields.put(fieldName(fieldRef), evaluate(right, state, depth));
                return;
            }

            SqlValue value = evaluate(right, state, depth);
            if (left instanceof Local local) {
                state.locals.put(local.getName(), value);
                if (value instanceof ObjectValue objectValue) {
                    state.builders.put(objectValue.builder.id, objectValue.builder);
                    state.textBuilders.put(objectValue.textBuilder.id, objectValue.textBuilder);
                }
            }
            return;
        }

        if (stmt instanceof JInvokeStmt invokeStmt) {
            invokeStmt.getInvokeExpr().ifPresent(invoke -> handleInvoke(invoke, state, depth));
        }
    }

    private void updateArray(JArrayRef arrayRef, Value right, ExecutionState state, int depth) {
        SqlValue base = state.locals.get(arrayRef.getBase().getName());
        ArrayValue arrayValue;
        if (base instanceof ArrayValue existingArray) {
            arrayValue = existingArray;
        } else {
            arrayValue = new ArrayValue();
            state.locals.put(arrayRef.getBase().getName(), arrayValue);
        }
        Integer index = intValue(arrayRef.getIndex());
        if (index != null) {
            arrayValue.elements.put(index, evaluate(right, state, depth));
        }
    }

    private SqlValue evaluate(Value value, ExecutionState state, int depth) {
        if (value == null) {
            return UnknownValue.INSTANCE;
        }
        if (value instanceof Local local) {
            return state.locals.getOrDefault(local.getName(), new PlaceholderValue(cleanName(local.getName())));
        }
        if (value instanceof StringConstant stringConstant) {
            return new FragmentValue(new TextFragment(stringConstant.getValue()));
        }
        if (value instanceof IntConstant intConstant) {
            return new FragmentValue(new TextFragment(Integer.toString(intConstant.getValue())));
        }
        if (value instanceof NullConstant) {
            return NullValue.INSTANCE;
        }
        if (value instanceof JParameterRef parameterRef) {
            return parameterRef.getIndex() < state.argumentValues.size()
                    ? state.argumentValues.get(parameterRef.getIndex())
                    : new ParameterValue("param" + parameterRef.getIndex(), parameterRef.getIndex());
        }
        if (value instanceof JInstanceFieldRef fieldRef) {
            return state.fields.getOrDefault(fieldName(fieldRef), new PlaceholderValue(cleanName(fieldName(fieldRef))));
        }
        if (value instanceof JArrayRef arrayRef) {
            SqlValue base = state.locals.get(arrayRef.getBase().getName());
            Integer index = intValue(arrayRef.getIndex());
            if (base instanceof ArrayValue arrayValue && index != null) {
                return arrayValue.elements.getOrDefault(index, UnknownValue.INSTANCE);
            }
            return UnknownValue.INSTANCE;
        }
        if (value instanceof JNewExpr newExpr) {
            ObjectValue objectValue = ObjectValue.of(newExpr.getType().toString());
            state.builders.put(objectValue.builder.id, objectValue.builder);
            state.textBuilders.put(objectValue.textBuilder.id, objectValue.textBuilder);
            return objectValue;
        }
        if (value instanceof JNewArrayExpr) {
            return new ArrayValue();
        }
        if (value instanceof JCastExpr castExpr) {
            return evaluate(castExpr.getOp(), state, depth);
        }
        if (value instanceof JAddExpr addExpr) {
            return concatValues(evaluate(addExpr.getOp1(), state, depth), evaluate(addExpr.getOp2(), state, depth));
        }
        if (value instanceof AbstractInvokeExpr invoke) {
            return handleInvoke(invoke, state, depth);
        }
        return new FragmentValue(new TextFragment(value.toString()));
    }

    private SqlValue handleInvoke(AbstractInvokeExpr invoke, ExecutionState state, int depth) {
        if (invoke instanceof JDynamicInvokeExpr dynamicInvoke) {
            return evaluateDynamicInvoke(dynamicInvoke, state, depth);
        }

        MethodSignature signature = invoke.getMethodSignature();
        String methodName = signature.getName();
        String className = signature.getDeclClassType().getFullyQualifiedName();

        if ("joining".equals(methodName) && "java.util.stream.Collectors".equals(className)) {
            String separator = invoke.getArgCount() > 0 ? renderInline(evaluate(invoke.getArg(0), state, depth)) : ",";
            return new JoinerValue(separator);
        }

        if ("valueOf".equals(methodName) && "java.lang.String".equals(className) && invoke.getArgCount() > 0) {
            return evaluate(invoke.getArg(0), state, depth);
        }

        SqlValue methodReceiver = null;
        if (invoke instanceof AbstractInstanceInvokeExpr instanceInvoke) {
            SqlValue base = evaluate(instanceInvoke.getBase(), state, depth);
            methodReceiver = base;

            if ("<init>".equals(methodName) && base instanceof ObjectValue objectValue
                    && objectValue.isTextBuilder()) {
                for (Immediate arg : invoke.getArgs()) {
                    objectValue.textBuilder.add(toFragment(evaluate(arg, state, depth)));
                }
                return objectValue;
            }

            // 匿名 SQL provider 编译为：new Provider$1(...); invokespecial <init>;
            // invokevirtual toString。跟进构造器即可还原初始化块。
            if ("<init>".equals(methodName) && base instanceof ObjectValue objectValue) {
                executeConstructor(signature, objectValue, invoke.getArgs(), state, depth);
                return objectValue;
            }

            if (isPrimitiveWrapperValueMethod(methodName)) {
                return base;
            }
            if (isStringPassthroughMethod(className, methodName)
                    && (base instanceof ParameterValue || base instanceof PlaceholderValue || base instanceof MethodValue)) {
                return base;
            }

            // 将 MyBatis SQL 构建器方法视为对自有模型的追加操作，
            // 而不是执行运行时的 org.apache.ibatis.jdbc.SQL 实现。
            if (SQL_METHODS.contains(methodName) && base instanceof ObjectValue objectValue) {
                addSqlClause(objectValue.builder, methodName, invoke.getArgs(), state, depth);
                return objectValue;
            }

            if ("toString".equals(methodName) && base instanceof ObjectValue objectValue && !objectValue.builder.clauses.isEmpty()) {
                return new ScriptValue(objectValue.builder);
            }
            if ("toString".equals(methodName) && base instanceof ObjectValue objectValue && objectValue.isTextBuilder()) {
                return new FragmentValue(new ConcatFragment(objectValue.textBuilder.fragments));
            }
            if ("append".equals(methodName) && base instanceof ObjectValue objectValue && objectValue.isTextBuilder()) {
                for (Immediate arg : invoke.getArgs()) {
                    objectValue.textBuilder.add(toFragment(evaluate(arg, state, depth)));
                }
                return objectValue;
            }

            if ("stream".equals(methodName)) {
                return new StreamValue(conditionOperand(base));
            }
            if ("map".equals(methodName) && base instanceof StreamValue) {
                return base;
            }
            if ("collect".equals(methodName) && base instanceof StreamValue streamValue) {
                // 将 collection.stream().map(...).collect(joining(",")) 识别为
                // MyBatis 的 foreach 循环，假定 mapper 函数对每个元素做字符串化。
                String separator = ",";
                if (invoke.getArgCount() > 0) {
                    SqlValue collector = evaluate(invoke.getArg(0), state, depth);
                    if (collector instanceof JoinerValue joinerValue) {
                        separator = joinerValue.separator;
                    }
                }
                return new ForEachValue(streamValue.collection, "item", separator);
            }
            if ("isEmpty".equals(methodName)) {
                return new PredicateValue(conditionOperand(base) + ".isEmpty()");
            }

            if (base instanceof ObjectValue) {
                Optional<SqlValue> calleeValue = analyzeCallee(signature, invoke.getArgs(), base, state, depth);
                if (calleeValue.isPresent()) {
                    return calleeValue.get();
                }
            }
        }

        if (!(invoke instanceof AbstractInstanceInvokeExpr)) {
            Optional<SqlValue> calleeValue = analyzeCallee(signature, invoke.getArgs(), null, state, depth);
            if (calleeValue.isPresent()) {
                return calleeValue.get();
            }
        }

        return new MethodValue(methodName, methodReceiver, invoke.getArgs().stream()
                .map(arg -> evaluate(arg, state, depth))
                .collect(Collectors.toCollection(ArrayList::new)));
    }

    private Optional<SqlValue> analyzeCallee(
            MethodSignature signature,
            List<Immediate> args,
            SqlValue base,
            ExecutionState state,
            int depth) {

        if (depth >= maxCallDepth || isExternalMethod(signature)) {
            return Optional.empty();
        }
        Optional<? extends SootMethod> callee = view.getMethod(signature);
        if (callee.isEmpty() || !callee.get().hasBody()) {
            return Optional.empty();
        }
        List<SqlValue> argumentValues = args.stream()
                .map(arg -> evaluate(arg, state, depth))
                .collect(Collectors.toCollection(ArrayList::new));
        ObjectValue thisObject = base instanceof ObjectValue objectValue ? objectValue : null;
        SqlValue value = executeMethod(callee.get(), argumentValues, thisObject, state.diagnostics, depth + 1);
        return value instanceof UnknownValue ? Optional.empty() : Optional.of(value);
    }

    private boolean isExternalMethod(MethodSignature signature) {
        String className = signature.getDeclClassType().getFullyQualifiedName();
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("org.apache.ibatis.");
    }

    private void executeConstructor(
            MethodSignature signature,
            ObjectValue objectValue,
            List<Immediate> args,
            ExecutionState state,
            int depth) {

        Optional<? extends SootMethod> constructor = view.getMethod(signature);
        if (constructor.isEmpty() || !constructor.get().hasBody()) {
            return;
        }
        List<SqlValue> argumentValues = args.stream()
                .map(arg -> evaluate(arg, state, depth))
                .collect(Collectors.toCollection(ArrayList::new));
        executeMethod(constructor.get(), argumentValues, objectValue, state.diagnostics, depth + 1);
    }

    private SqlValue evaluateDynamicInvoke(JDynamicInvokeExpr invoke, ExecutionState state, int depth) {
        MethodSignature bootstrap = invoke.getBootstrapMethodSignature();
        boolean stringConcat = "makeConcatWithConstants".equals(bootstrap.getName())
                || "makeConcat".equals(bootstrap.getName());
        if (!stringConcat) {
            return UnknownValue.INSTANCE;
        }

        String recipe = null;
        for (Value bootstrapArg : invoke.getBootstrapArgs()) {
            if (bootstrapArg instanceof StringConstant stringConstant) {
                recipe = stringConstant.getValue();
                break;
            }
        }

        if (recipe == null) {
            List<SqlFragment> fragments = invoke.getArgs().stream()
                    .map(arg -> toFragment(evaluate(arg, state, depth)))
                    .collect(Collectors.toCollection(ArrayList::new));
            return new FragmentValue(new ConcatFragment(fragments));
        }

        // Java 9+ 的字符串拼接会存储一个 recipe，其中转义字符\u0001 标记运行期参数
        // （\u0002 标记 bootstrap 常量）。这里把该 recipe 还原为有序的 SQL 片段。
        List<SqlFragment> fragments = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int argIndex = 0;
        for (int i = 0; i < recipe.length(); i++) {
            char ch = recipe.charAt(i);
            if (ch == '\u0001') {
                if (!text.isEmpty()) {
                    fragments.add(new TextFragment(text.toString()));
                    text = new StringBuilder();
                }
                if (argIndex < invoke.getArgCount()) {
                    fragments.add(toFragment(evaluate(invoke.getArg(argIndex++), state, depth)));
                }
            } else if (ch != '\u0002') {
                text.append(ch);
            }
        }
        if (!text.isEmpty()) {
            fragments.add(new TextFragment(text.toString()));
        }
        return new FragmentValue(new ConcatFragment(fragments));
    }

    private void addSqlClause(
            SqlBuilder builder,
            String methodName,
            List<Immediate> args,
            ExecutionState state,
            int depth) {

        List<SqlFragment> fragments = new ArrayList<>();
        for (Immediate arg : args) {
            SqlValue value = evaluate(arg, state, depth);
            fragments.addAll(toFragments(value));
        }

        ClauseKind kind = clauseKind(methodName);
        if ("VALUES".equals(methodName)) {
            if (!fragments.isEmpty()) {
                builder.add(new SqlClause(ClauseKind.INSERT_COLUMNS, fragments.get(0)));
            }
            if (fragments.size() > 1) {
                builder.add(new SqlClause(ClauseKind.INSERT_VALUES, fragments.get(1)));
            }
            return;
        }
        if ("ADD_ROW".equals(methodName)) {
            builder.add(new SqlClause(ClauseKind.ADD_ROW, TextFragment.EMPTY));
            return;
        }
        if ("AND".equals(methodName) || "OR".equals(methodName)) {
            ClauseKind connectorKind = builder.lastConditionKind == ClauseKind.HAVING
                    ? ClauseKind.HAVING_CONNECTOR
                    : ClauseKind.WHERE_CONNECTOR;
            builder.add(new SqlClause(connectorKind, new TextFragment(methodName)));
            return;
        }
        if (kind == ClauseKind.JOIN) {
            String prefix = joinPrefix(methodName);
            for (SqlFragment fragment : fragments) {
                builder.add(new SqlClause(kind, new ConcatFragment(List.of(new TextFragment(prefix), fragment))));
            }
            return;
        }

        for (SqlFragment fragment : fragments) {
            builder.add(new SqlClause(kind, fragment));
        }
    }

    private ClauseKind clauseKind(String methodName) {
        return switch (methodName) {
            case "SELECT_DISTINCT" -> ClauseKind.SELECT_DISTINCT;
            case "SELECT" -> ClauseKind.SELECT;
            case "FROM" -> ClauseKind.FROM;
            case "JOIN", "INNER_JOIN", "LEFT_JOIN", "RIGHT_JOIN", "FULL_JOIN",
                    "LEFT_OUTER_JOIN", "RIGHT_OUTER_JOIN", "OUTER_JOIN" -> ClauseKind.JOIN;
            case "WHERE" -> ClauseKind.WHERE;
            case "ORDER_BY" -> ClauseKind.ORDER_BY;
            case "GROUP_BY" -> ClauseKind.GROUP_BY;
            case "HAVING" -> ClauseKind.HAVING;
            case "INSERT_INTO" -> ClauseKind.INSERT_INTO;
            case "INTO_COLUMNS" -> ClauseKind.INSERT_COLUMNS;
            case "INTO_VALUES" -> ClauseKind.INSERT_VALUES;
            case "UPDATE" -> ClauseKind.UPDATE;
            case "SET" -> ClauseKind.SET;
            case "DELETE_FROM" -> ClauseKind.DELETE_FROM;
            case "LIMIT" -> ClauseKind.LIMIT;
            case "OFFSET" -> ClauseKind.OFFSET;
            case "FETCH_FIRST_ROWS_ONLY" -> ClauseKind.FETCH_FIRST_ROWS_ONLY;
            case "OFFSET_ROWS" -> ClauseKind.OFFSET_ROWS;
            default -> ClauseKind.RAW;
        };
    }

    private String joinPrefix(String methodName) {
        return switch (methodName) {
            case "INNER_JOIN" -> "INNER JOIN ";
            case "LEFT_JOIN" -> "LEFT JOIN ";
            case "RIGHT_JOIN" -> "RIGHT JOIN ";
            case "FULL_JOIN" -> "FULL JOIN ";
            case "LEFT_OUTER_JOIN" -> "LEFT OUTER JOIN ";
            case "RIGHT_OUTER_JOIN" -> "RIGHT OUTER JOIN ";
            case "OUTER_JOIN" -> "OUTER JOIN ";
            default -> "JOIN ";
        };
    }

    private boolean isPrimitiveWrapperValueMethod(String methodName) {
        return switch (methodName) {
            case "intValue", "longValue", "shortValue", "byteValue", "floatValue", "doubleValue",
                    "booleanValue", "charValue" -> true;
            default -> false;
        };
    }

    private boolean isStringPassthroughMethod(String className, String methodName) {
        if (!"java.lang.String".equals(className)) {
            return false;
        }
        return switch (methodName) {
            case "replace", "replaceAll", "replaceFirst", "trim", "strip", "stripLeading",
                    "stripTrailing", "toLowerCase", "toUpperCase" -> true;
            default -> false;
        };
    }

    private SqlValue concatValues(SqlValue left, SqlValue right) {
        List<SqlFragment> fragments = new ArrayList<>();
        appendConcatFragment(fragments, toFragment(left));
        appendConcatFragment(fragments, toFragment(right));
        return new FragmentValue(new ConcatFragment(fragments));
    }

    private void appendConcatFragment(List<SqlFragment> fragments, SqlFragment fragment) {
        if (fragment instanceof ConcatFragment concatFragment) {
            fragments.addAll(concatFragment.fragments);
        } else {
            fragments.add(fragment);
        }
    }

    private List<SqlFragment> toFragments(SqlValue value) {
        if (value instanceof ArrayValue arrayValue) {
            return arrayValue.elements.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> toFragment(entry.getValue()))
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        return List.of(toFragment(value));
    }

    private SqlFragment toFragment(SqlValue value) {
        if (value instanceof FragmentValue fragmentValue) {
            return fragmentValue.fragment;
        }
        if (value instanceof ParameterValue parameterValue) {
            return new TextFragment("#{" + parameterValue.name + "}");
        }
        if (value instanceof PlaceholderValue placeholderValue) {
            return new TextFragment("#{" + placeholderValue.name + "}");
        }
        if (value instanceof ForEachValue forEachValue) {
            return new ForEachFragment(forEachValue.collection, forEachValue.item,
                    forEachValue.separator, new TextFragment("#{" + forEachValue.item + "}"));
        }
        if (value instanceof ScriptValue scriptValue) {
            return new TextFragment(renderBuilder(scriptValue.builder));
        }
        if (value instanceof MethodValue methodValue) {
            return new TextFragment("#{" + cleanName(methodValue.methodName) + "}");
        }
        if (value instanceof NullValue) {
            return new TextFragment("null");
        }
        return new TextFragment("#{unknown}");
    }

    private String renderReturnValue(SqlValue value) {
        if (value instanceof ScriptValue scriptValue) {
            return renderBuilder(scriptValue.builder);
        }
        if (value instanceof FragmentValue fragmentValue) {
            return wrapScript(renderInline(fragmentValue.fragment));
        }
        if (value instanceof ObjectValue objectValue && !objectValue.builder.clauses.isEmpty()) {
            return renderBuilder(objectValue.builder);
        }
        return null;
    }

    private String renderBuilder(SqlBuilder builder) {
        if (!collect(builder, ClauseKind.INSERT_INTO).isEmpty()) {
            return renderInsert(builder);
        }
        if (!collect(builder, ClauseKind.UPDATE).isEmpty()) {
            return renderUpdate(builder);
        }
        if (!collect(builder, ClauseKind.DELETE_FROM).isEmpty()) {
            return renderDelete(builder);
        }
        return renderSelect(builder);
    }

    private String renderSelect(SqlBuilder builder) {
        List<String> lines = new ArrayList<>();

        // 按固定的子句顺序渲染规整后的 SQL 构建器模型。动态片段仍嵌套在
        // 原分支写入时的子句位置。
        List<SqlFragment> distinctSelects = collect(builder, ClauseKind.SELECT_DISTINCT);
        List<SqlFragment> selects = collect(builder, ClauseKind.SELECT);
        if (!distinctSelects.isEmpty()) {
            lines.add("SELECT DISTINCT " + renderJoined(distinctSelects, ", "));
        } else if (!selects.isEmpty()) {
            lines.add("SELECT " + renderJoined(selects, ", "));
        }

        for (SqlFragment from : collect(builder, ClauseKind.FROM)) {
            lines.add("FROM " + renderInline(from));
        }
        for (SqlFragment join : collect(builder, ClauseKind.JOIN)) {
            lines.add(renderInline(join));
        }

        List<SqlClause> where = collectConditions(builder, ClauseKind.WHERE, ClauseKind.WHERE_CONNECTOR);
        if (!where.isEmpty()) {
            appendWhereClause(lines, where);
        }

        appendClause(lines, "GROUP BY ", collect(builder, ClauseKind.GROUP_BY), ", ");
        appendConditionClause(lines, "HAVING", collectConditions(builder, ClauseKind.HAVING, ClauseKind.HAVING_CONNECTOR));
        appendClause(lines, "ORDER BY ", collect(builder, ClauseKind.ORDER_BY), ", ");
        appendLimitClauses(lines, builder, true);

        for (SqlFragment raw : collect(builder, ClauseKind.RAW)) {
            lines.add(renderBlock(raw, 0, ClauseKind.RAW));
        }

        return wrapScript(String.join("\n", lines));
    }

    private String renderInsert(SqlBuilder builder) {
        List<String> lines = new ArrayList<>();
        lines.add("INSERT INTO " + renderJoined(collect(builder, ClauseKind.INSERT_INTO), ", "));

        List<SqlFragment> columns = collect(builder, ClauseKind.INSERT_COLUMNS);
        if (!columns.isEmpty()) {
            lines.add("(" + renderJoined(columns, ", ") + ")");
        }

        List<List<SqlFragment>> rows = collectInsertValueRows(builder);
        for (int i = 0; i < rows.size(); i++) {
            String prefix = i == 0 ? "VALUES " : ", ";
            lines.add(prefix + "(" + renderJoined(rows.get(i), ", ") + ")");
        }

        return wrapScript(String.join("\n", lines));
    }

    private String renderUpdate(SqlBuilder builder) {
        List<String> lines = new ArrayList<>();
        lines.add("UPDATE " + renderJoined(collect(builder, ClauseKind.UPDATE), ", "));
        for (SqlFragment join : collect(builder, ClauseKind.JOIN)) {
            lines.add(renderInline(join));
        }
        appendSetClause(lines, collect(builder, ClauseKind.SET));
        appendWhereClause(lines, collectConditions(builder, ClauseKind.WHERE, ClauseKind.WHERE_CONNECTOR));
        appendLimitClauses(lines, builder, false);
        return wrapScript(String.join("\n", lines));
    }

    private String renderDelete(SqlBuilder builder) {
        List<String> lines = new ArrayList<>();
        lines.add("DELETE FROM " + renderJoined(collect(builder, ClauseKind.DELETE_FROM), ", "));
        appendWhereClause(lines, collectConditions(builder, ClauseKind.WHERE, ClauseKind.WHERE_CONNECTOR));
        appendLimitClauses(lines, builder, false);
        return wrapScript(String.join("\n", lines));
    }

    private List<SqlFragment> collect(SqlBuilder builder, ClauseKind kind) {
        return builder.clauses.stream()
                .filter(clause -> clause.kind == kind)
                .map(clause -> clause.fragment)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private List<SqlClause> collectConditions(SqlBuilder builder, ClauseKind valueKind, ClauseKind connectorKind) {
        return builder.clauses.stream()
                .filter(clause -> clause.kind == valueKind || clause.kind == connectorKind)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private List<List<SqlFragment>> collectInsertValueRows(SqlBuilder builder) {
        List<List<SqlFragment>> rows = new ArrayList<>();
        List<SqlFragment> current = new ArrayList<>();
        for (SqlClause clause : builder.clauses) {
            if (clause.kind == ClauseKind.ADD_ROW) {
                if (!current.isEmpty()) {
                    rows.add(current);
                }
                current = new ArrayList<>();
            } else if (clause.kind == ClauseKind.INSERT_VALUES) {
                current.add(clause.fragment);
            }
        }
        if (!current.isEmpty()) {
            rows.add(current);
        }
        return rows;
    }

    private void appendClause(List<String> lines, String prefix, List<SqlFragment> fragments, String delimiter) {
        if (!fragments.isEmpty()) {
            lines.add(prefix + renderJoined(fragments, delimiter));
        }
    }

    private void appendSetClause(List<String> lines, List<SqlFragment> fragments) {
        if (fragments.isEmpty()) {
            return;
        }
        if (fragments.stream().noneMatch(SqlFragment::isBlock)) {
            lines.add("SET " + renderJoined(fragments, ", "));
            return;
        }

        // 动态 SET 项的分隔符必须放在 MyBatis 标签内部；否则被禁用的 <if>
        // 会在渲染出的赋值之间留下悬空逗号。
        lines.add("SET ");
        for (int i = 0; i < fragments.size(); i++) {
            lines.add(renderSetFragment(fragments.get(i), i < fragments.size() - 1));
        }
    }

    private String renderSetFragment(SqlFragment fragment, boolean appendComma) {
        if (fragment instanceof IfFragment ifFragment) {
            List<String> lines = new ArrayList<>();
            lines.add("<if test=\"" + escapeXml(ifFragment.test) + "\">");
            lines.add(renderSetBody(ifFragment.fragments, appendComma, 2));
            lines.add("</if>");
            return lines.stream().filter(s -> !s.isEmpty()).collect(Collectors.joining("\n"));
        }
        if (fragment instanceof ChooseFragment chooseFragment) {
            List<String> lines = new ArrayList<>();
            lines.add("<choose>");
            lines.add("  <when test=\"" + escapeXml(chooseFragment.test) + "\">");
            lines.add(renderSetBody(chooseFragment.whenFragments, appendComma, 4));
            lines.add("  </when>");
            if (!chooseFragment.otherwiseFragments.isEmpty()) {
                lines.add("  <otherwise>");
                lines.add(renderSetBody(chooseFragment.otherwiseFragments, appendComma, 4));
                lines.add("  </otherwise>");
            }
            lines.add("</choose>");
            return lines.stream().filter(s -> !s.isEmpty()).collect(Collectors.joining("\n"));
        }
        String rendered = renderInline(fragment);
        return appendComma ? rendered + "," : rendered;
    }

    private String renderSetBody(List<SqlFragment> fragments, boolean appendComma, int indent) {
        String pad = " ".repeat(Math.max(0, indent));
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < fragments.size(); i++) {
            SqlFragment fragment = fragments.get(i);
            boolean needsComma = i < fragments.size() - 1 || appendComma;
            if (fragment.isBlock()) {
                lines.add(indent(renderSetFragment(fragment, needsComma), indent));
            } else {
                String rendered = renderInline(fragment);
                lines.add(pad + rendered + (needsComma ? "," : ""));
            }
        }
        return String.join("\n", lines);
    }

    private void appendWhereClause(List<String> lines, List<SqlClause> clauses) {
        if (clauses.isEmpty()) {
            return;
        }
        if (!requiresWhereTag(clauses)) {
            appendConditionClause(lines, "WHERE", clauses);
            return;
        }

        lines.add("<where>");
        String connector = "AND";
        boolean firstCondition = true;
        for (SqlClause clause : clauses) {
            if (clause.kind == ClauseKind.WHERE_CONNECTOR) {
                connector = renderInline(clause.fragment);
                continue;
            }
            String effectiveConnector = firstCondition && !(clause.fragment instanceof IfFragment) ? "" : connector;
            lines.add(renderWhereTagFragment(clause.fragment, effectiveConnector));
            firstCondition = false;
            connector = "AND";
        }
        lines.add("</where>");
    }

    private boolean requiresWhereTag(List<SqlClause> clauses) {
        return clauses.stream()
                .filter(clause -> clause.kind == ClauseKind.WHERE)
                .anyMatch(clause -> clause.fragment instanceof IfFragment);
    }

    private void appendConditionClause(List<String> lines, String keyword, List<SqlClause> clauses) {
        boolean first = true;
        String connector = "AND";
        for (SqlClause clause : clauses) {
            if (clause.kind == ClauseKind.WHERE_CONNECTOR || clause.kind == ClauseKind.HAVING_CONNECTOR) {
                connector = renderInline(clause.fragment);
                continue;
            }
            SqlFragment fragment = clause.fragment;
            String prefix = first ? keyword : connector;
            if (fragment.isBlock()) {
                if (first) {
                    lines.add(prefix);
                    lines.add(renderBlock(fragment, 0, clause.kind));
                } else {
                    lines.add(renderConnectedConditionBlock(fragment, connector, clause.kind));
                }
            } else {
                lines.add(prefix + " " + renderInline(fragment));
            }
            first = false;
            connector = "AND";
        }
    }

    private void appendLimitClauses(List<String> lines, SqlBuilder builder, boolean allowOffset) {
        List<SqlFragment> limits = collect(builder, ClauseKind.LIMIT);
        if (!limits.isEmpty()) {
            lines.add(renderPrefixedFragment("LIMIT", limits.get(limits.size() - 1), ClauseKind.LIMIT));
        }
        if (allowOffset) {
            List<SqlFragment> offsets = collect(builder, ClauseKind.OFFSET);
            if (!offsets.isEmpty()) {
                lines.add(renderPrefixedFragment("OFFSET", offsets.get(offsets.size() - 1), ClauseKind.OFFSET));
            }
            List<SqlFragment> offsetRows = collect(builder, ClauseKind.OFFSET_ROWS);
            if (!offsetRows.isEmpty()) {
                lines.add(renderPrefixedFragment("OFFSET", offsetRows.get(offsetRows.size() - 1), ClauseKind.OFFSET_ROWS, "ROWS"));
            }
            List<SqlFragment> fetchFirst = collect(builder, ClauseKind.FETCH_FIRST_ROWS_ONLY);
            if (!fetchFirst.isEmpty()) {
                lines.add(renderPrefixedFragment("FETCH FIRST", fetchFirst.get(fetchFirst.size() - 1),
                        ClauseKind.FETCH_FIRST_ROWS_ONLY, "ROWS ONLY"));
            }
        }
    }

    private String renderPrefixedFragment(String prefix, SqlFragment fragment, ClauseKind context) {
        return renderPrefixedFragment(prefix, fragment, context, null);
    }

    private String renderPrefixedFragment(String prefix, SqlFragment fragment, ClauseKind context, String suffix) {
        if (fragment instanceof IfFragment ifFragment) {
            List<String> lines = new ArrayList<>();
            lines.add("<if test=\"" + escapeXml(ifFragment.test) + "\">");
            lines.add(indent(renderPrefixedBody(prefix, ifFragment.fragments, suffix), 2));
            lines.add("</if>");
            return lines.stream().filter(s -> !s.isEmpty()).collect(Collectors.joining("\n"));
        }
        if (fragment instanceof ChooseFragment chooseFragment) {
            List<String> lines = new ArrayList<>();
            lines.add("<choose>");
            lines.add("  <when test=\"" + escapeXml(chooseFragment.test) + "\">");
            lines.add(indent(renderPrefixedBody(prefix, chooseFragment.whenFragments, suffix), 2));
            lines.add("  </when>");
            if (!chooseFragment.otherwiseFragments.isEmpty()) {
                lines.add("  <otherwise>");
                lines.add(indent(renderPrefixedBody(prefix, chooseFragment.otherwiseFragments, suffix), 2));
                lines.add("  </otherwise>");
            }
            lines.add("</choose>");
            return lines.stream().filter(s -> !s.isEmpty()).collect(Collectors.joining("\n"));
        }
        if (fragment.isBlock()) {
            return prefix + "\n" + renderBlock(fragment, 0, context);
        }
        String rendered = prefix + " " + renderInline(fragment);
        return suffix == null ? rendered : rendered + " " + suffix;
    }

    private String renderPrefixedBody(String prefix, List<SqlFragment> fragments, String suffix) {
        if (fragments.isEmpty()) {
            return "";
        }
        String body = fragments.stream()
                .map(this::renderInline)
                .collect(Collectors.joining(" "));
        String rendered = prefix + " " + body;
        return suffix == null ? rendered : rendered + " " + suffix;
    }

    private String indent(String text, int spaces) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String pad = " ".repeat(Math.max(0, spaces));
        return Arrays.stream(text.split("\\R", -1))
                .map(line -> line.isEmpty() ? line : pad + line)
                .collect(Collectors.joining("\n"));
    }

    private String renderJoined(List<SqlFragment> fragments, String delimiter) {
        return fragments.stream()
                .map(this::renderInline)
                .collect(Collectors.joining(delimiter));
    }

    private String renderInline(SqlValue value) {
        return renderInline(toFragment(value));
    }

    private String renderInline(SqlFragment fragment) {
        if (fragment instanceof TextFragment textFragment) {
            return textFragment.text;
        }
        if (fragment instanceof ConcatFragment concatFragment) {
            return concatFragment.fragments.stream().map(this::renderInline).collect(Collectors.joining());
        }
        if (fragment instanceof ForEachFragment forEachFragment) {
            return "<foreach collection=\"" + forEachFragment.collection + "\" item=\"" + forEachFragment.item
                    + "\" separator=\"" + forEachFragment.separator + "\">"
                    + renderInline(forEachFragment.body) + "</foreach>";
        }
        return renderBlock(fragment, 0, ClauseKind.RAW);
    }

    private String renderBlock(SqlFragment fragment, int indent, ClauseKind context) {
        String pad = " ".repeat(Math.max(0, indent));
        if (fragment instanceof ChooseFragment chooseFragment) {
            List<String> lines = new ArrayList<>();
            lines.add(pad + "<choose>");
            lines.add(pad + "  <when test=\"" + escapeXml(chooseFragment.test) + "\">");
            lines.add(renderBranchFragments(chooseFragment.whenFragments, indent + 4, context));
            lines.add(pad + "  </when>");
            if (!chooseFragment.otherwiseFragments.isEmpty()) {
                lines.add(pad + "  <otherwise>");
                lines.add(renderBranchFragments(chooseFragment.otherwiseFragments, indent + 4, context));
                lines.add(pad + "  </otherwise>");
            }
            lines.add(pad + "</choose>");
            return lines.stream().filter(s -> !s.isEmpty()).collect(Collectors.joining("\n"));
        }
        if (fragment instanceof IfFragment ifFragment) {
            List<String> lines = new ArrayList<>();
            lines.add(pad + "<if test=\"" + escapeXml(ifFragment.test) + "\">");
            lines.add(renderBranchFragments(ifFragment.fragments, indent + 2, context));
            lines.add(pad + "</if>");
            return lines.stream().filter(s -> !s.isEmpty()).collect(Collectors.joining("\n"));
        }
        if (fragment instanceof RawClausesFragment rawClausesFragment) {
            return rawClausesFragment.clauses.stream()
                    .map(clause -> pad + renderClauseAsLine(clause))
                    .collect(Collectors.joining("\n"));
        }
        return pad + renderInline(fragment);
    }

    private String renderConnectedConditionBlock(SqlFragment fragment, String connector, ClauseKind context) {
        String prefix = connector == null || connector.isBlank() ? "" : connector;
        if (fragment instanceof IfFragment ifFragment) {
            List<String> lines = new ArrayList<>();
            lines.add("<if test=\"" + escapeXml(ifFragment.test) + "\">");
            lines.add(indent(renderConditionBody(ifFragment.fragments, prefix, context), 2));
            lines.add("</if>");
            return lines.stream().filter(s -> !s.isEmpty()).collect(Collectors.joining("\n"));
        }
        if (fragment instanceof ChooseFragment chooseFragment) {
            List<String> lines = new ArrayList<>();
            lines.add("<choose>");
            lines.add("  <when test=\"" + escapeXml(chooseFragment.test) + "\">");
            lines.add(indent(renderConditionBody(chooseFragment.whenFragments, prefix, context), 4));
            lines.add("  </when>");
            if (!chooseFragment.otherwiseFragments.isEmpty()) {
                lines.add("  <otherwise>");
                lines.add(indent(renderConditionBody(chooseFragment.otherwiseFragments, prefix, context), 4));
                lines.add("  </otherwise>");
            }
            lines.add("</choose>");
            return lines.stream().filter(s -> !s.isEmpty()).collect(Collectors.joining("\n"));
        }
        return prefix.isBlank()
                ? renderBlock(fragment, 0, context)
                : prefix + "\n" + renderBlock(fragment, 0, context);
    }

    private String renderWhereTagFragment(SqlFragment fragment, String connector) {
        if (fragment instanceof IfFragment ifFragment) {
            List<String> lines = new ArrayList<>();
            lines.add("<if test=\"" + escapeXml(ifFragment.test) + "\">");
            lines.add(renderConditionBody(ifFragment.fragments, whereConnectorPrefix(connector), ClauseKind.WHERE));
            lines.add("</if>");
            return lines.stream().filter(s -> !s.isEmpty()).collect(Collectors.joining("\n"));
        }
        if (fragment instanceof ChooseFragment chooseFragment) {
            List<String> lines = new ArrayList<>();
            lines.add("<choose>");
            lines.add("  <when test=\"" + escapeXml(chooseFragment.test) + "\">");
            lines.add(renderConditionBody(chooseFragment.whenFragments, " " + whereConnectorPrefix(connector), ClauseKind.WHERE));
            lines.add("  </when>");
            if (!chooseFragment.otherwiseFragments.isEmpty()) {
                lines.add("  <otherwise>");
                lines.add(renderConditionBody(chooseFragment.otherwiseFragments, " " + whereConnectorPrefix(connector), ClauseKind.WHERE));
                lines.add("  </otherwise>");
            }
            lines.add("</choose>");
            return lines.stream().filter(s -> !s.isEmpty()).collect(Collectors.joining("\n"));
        }
        String prefix = whereConnectorPrefix(connector);
        return prefix.isBlank() ? " " + renderInline(fragment) : prefix + " " + renderInline(fragment);
    }

    private String whereConnectorPrefix(String connector) {
        return connector == null || connector.isBlank() ? "" : " " + connector;
    }

    private String renderConditionBody(List<SqlFragment> fragments, String firstPrefix, ClauseKind context) {
        List<String> lines = new ArrayList<>();
        boolean first = true;
        for (SqlFragment fragment : fragments) {
            if (fragment.isBlock()) {
                lines.add(renderBlock(fragment, 0, context));
            } else {
                String prefix = first ? firstPrefix : " AND";
                lines.add(prefix + " " + renderInline(fragment));
            }
            first = false;
        }
        return String.join("\n", lines);
    }

    private String renderBranchFragments(List<SqlFragment> fragments, int indent, ClauseKind context) {
        String pad = " ".repeat(Math.max(0, indent));
        if (fragments.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        boolean first = true;
        for (SqlFragment fragment : fragments) {
            if (fragment.isBlock()) {
                lines.add(renderBlock(fragment, indent, context));
            } else if (context == ClauseKind.WHERE) {
                lines.add(pad + (first ? "" : "AND ") + renderInline(fragment));
            } else {
                lines.add(pad + renderInline(fragment));
            }
            first = false;
        }
        return String.join("\n", lines);
    }

    private String renderClauseAsLine(SqlClause clause) {
        return switch (clause.kind) {
            case SELECT -> "SELECT " + renderInline(clause.fragment);
            case SELECT_DISTINCT -> "SELECT DISTINCT " + renderInline(clause.fragment);
            case FROM -> "FROM " + renderInline(clause.fragment);
            case WHERE -> "WHERE " + renderInline(clause.fragment);
            case ORDER_BY -> "ORDER BY " + renderInline(clause.fragment);
            case GROUP_BY -> "GROUP BY " + renderInline(clause.fragment);
            case HAVING -> "HAVING " + renderInline(clause.fragment);
            case INSERT_INTO -> "INSERT INTO " + renderInline(clause.fragment);
            case INSERT_COLUMNS -> renderInline(clause.fragment);
            case INSERT_VALUES -> renderInline(clause.fragment);
            case UPDATE -> "UPDATE " + renderInline(clause.fragment);
            case SET -> "SET " + renderInline(clause.fragment);
            case DELETE_FROM -> "DELETE FROM " + renderInline(clause.fragment);
            case LIMIT -> "LIMIT " + renderInline(clause.fragment);
            case OFFSET -> "OFFSET " + renderInline(clause.fragment);
            case FETCH_FIRST_ROWS_ONLY -> "FETCH FIRST " + renderInline(clause.fragment) + " ROWS ONLY";
            case OFFSET_ROWS -> "OFFSET " + renderInline(clause.fragment) + " ROWS";
            default -> renderInline(clause.fragment);
        };
    }

    private String wrapScript(String sql) {
        String trimmed = sql == null ? "" : sql.trim();
        if (trimmed.startsWith("<script>") && trimmed.endsWith("</script>")) {
            return trimmed;
        }
        return "<script>\n" + trimmed + "\n</script>";
    }

    private String renderCondition(AbstractConditionExpr condition, boolean branchTaken, ExecutionState state, int depth) {
        String symbol = condition.getSymbol().trim();
        if (!branchTaken) {
            symbol = invert(symbol);
        }
        SqlValue left = evaluate(condition.getOp1(), state, depth);
        SqlValue right = evaluate(condition.getOp2(), state, depth);

        // 返回布尔值的方法调用会被编译为与 0 的比较。这里把它们还原为
        // !ids.isEmpty() 这类常规的 MyBatis test 表达式。
        if (left instanceof PredicateValue predicateValue && isZero(right)) {
            if ("!=".equals(symbol)) {
                return predicateValue.expression;
            }
            if ("==".equals(symbol)) {
                return "!" + predicateValue.expression;
            }
        }
        if (right instanceof PredicateValue predicateValue && isZero(left)) {
            if ("!=".equals(symbol)) {
                return predicateValue.expression;
            }
            if ("==".equals(symbol)) {
                return "!" + predicateValue.expression;
            }
        }

        return conditionOperand(left) + " " + symbol + " " + conditionOperand(right);
    }

    private String conditionOperand(SqlValue value) {
        if (value instanceof ParameterValue parameterValue) {
            return parameterValue.name;
        }
        if (value instanceof PlaceholderValue placeholderValue) {
            return placeholderValue.name;
        }
        if (value instanceof PredicateValue predicateValue) {
            return predicateValue.expression;
        }
        if (value instanceof FragmentValue fragmentValue) {
            return renderInline(fragmentValue.fragment);
        }
        if (value instanceof ForEachValue forEachValue) {
            return forEachValue.collection;
        }
        if (value instanceof CollectionItemValue collectionItemValue) {
            return collectionItemValue.collection;
        }
        if (value instanceof NullValue) {
            return "null";
        }
        if (value instanceof MethodValue methodValue) {
            return methodExpression(methodValue);
        }
        return "unknown";
    }

    private String methodExpression(MethodValue methodValue) {
        String receiver = methodValue.receiver == null ? "" : receiverOperand(methodValue.receiver) + ".";
        if (methodValue.receiver != null && methodValue.args.isEmpty()) {
            Optional<String> property = getterPropertyName(methodValue.methodName);
            if (property.isPresent()) {
                return receiver + property.get();
            }
        }
        String args = methodValue.args.stream()
                .map(this::conditionOperand)
                .collect(Collectors.joining(", "));
        return receiver + methodValue.methodName + "(" + args + ")";
    }

    private Optional<String> getterPropertyName(String methodName) {
        if (methodName == null || !methodName.startsWith("get") || methodName.length() <= 3) {
            return Optional.empty();
        }
        String property = methodName.substring(3);
        return Optional.of(Character.toLowerCase(property.charAt(0)) + property.substring(1));
    }

    private String receiverOperand(SqlValue value) {
        if (value instanceof ParameterValue parameterValue) {
            String fallback = "param" + parameterValue.index;
            return fallback.equals(parameterValue.name) ? "@" + fallback : parameterValue.name;
        }
        if (value instanceof MethodValue methodValue) {
            return methodExpression(methodValue);
        }
        return conditionOperand(value);
    }

    private boolean isZero(SqlValue value) {
        return value instanceof FragmentValue fragmentValue
                && fragmentValue.fragment instanceof TextFragment textFragment
                && "0".equals(textFragment.text);
    }

    private String invert(String symbol) {
        return switch (symbol) {
            case "==" -> "!=";
            case "!=" -> "==";
            case "<" -> ">=";
            case "<=" -> ">";
            case ">" -> "<=";
            case ">=" -> "<";
            default -> "!(" + symbol + ")";
        };
    }

    private int firstTargetIndex(Body body, List<Stmt> stmts, JIfStmt ifStmt) {
        List<Stmt> targets = ifStmt.getTargetStmts(body);
        if (targets.isEmpty()) {
            return -1;
        }
        return stmts.indexOf(targets.get(0));
    }

    private int firstTargetIndex(Body body, List<Stmt> stmts, JGotoStmt gotoStmt) {
        List<Stmt> targets = gotoStmt.getTargetStmts(body);
        if (targets.isEmpty()) {
            return -1;
        }
        return stmts.indexOf(targets.get(0));
    }

    private Integer intValue(Value value) {
        if (value instanceof IntConstant intConstant) {
            return intConstant.getValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isTextBuilderType(String typeName) {
        return "java.lang.StringBuilder".equals(typeName)
                || "java.lang.StringBuffer".equals(typeName);
    }

    private String fieldName(JFieldRef fieldRef) {
        return fieldRef.getFieldSignature().getName();
    }

    private String parameterName(String localName, int index) {
        String cleaned = cleanName(localName);
        if (cleaned.isBlank() || isSyntheticLocalName(cleaned)) {
            return "param" + index;
        }
        return cleaned;
    }

    private boolean isSyntheticLocalName(String name) {
        return name == null
                || name.isBlank()
                || name.matches("[a-z]\\d+")
                || name.startsWith("$stack")
                || name.startsWith("stack");
    }

    private String cleanName(String name) {
        if (name == null || name.isBlank()) {
            return "param";
        }
        String cleaned = name;
        if (cleaned.startsWith("val$")) {
            cleaned = cleaned.substring(4);
        }
        if (cleaned.startsWith("$")) {
            cleaned = cleaned.substring(1);
        }
        cleaned = cleaned.replace("$", "");
        if ("this0".equals(cleaned) || "this".equals(cleaned)) {
            return cleaned;
        }
        return cleaned.isBlank() ? "param" : cleaned;
    }

    private String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private enum ClauseKind {
        SELECT,
        SELECT_DISTINCT,
        FROM,
        JOIN,
        WHERE,
        GROUP_BY,
        HAVING,
        ORDER_BY,
        INSERT_INTO,
        INSERT_COLUMNS,
        INSERT_VALUES,
        ADD_ROW,
        UPDATE,
        SET,
        DELETE_FROM,
        LIMIT,
        OFFSET,
        FETCH_FIRST_ROWS_ONLY,
        OFFSET_ROWS,
        WHERE_CONNECTOR,
        HAVING_CONNECTOR,
        RAW
    }

    private interface SqlValue {
    }

    private interface SqlFragment {
        default boolean isBlock() {
            return false;
        }
    }

    private record TextFragment(String text) implements SqlFragment {
        private static final TextFragment EMPTY = new TextFragment("");
    }

    private record ConcatFragment(List<SqlFragment> fragments) implements SqlFragment {
    }

    private record ChooseFragment(String test, List<SqlFragment> whenFragments, List<SqlFragment> otherwiseFragments)
            implements SqlFragment {
        @Override
        public boolean isBlock() {
            return true;
        }
    }

    private record IfFragment(String test, List<SqlFragment> fragments) implements SqlFragment {
        @Override
        public boolean isBlock() {
            return true;
        }
    }

    private record ForEachFragment(String collection, String item, String separator, SqlFragment body)
            implements SqlFragment {
    }

    private record RawClausesFragment(List<SqlClause> clauses) implements SqlFragment {
        @Override
        public boolean isBlock() {
            return true;
        }
    }

    private record FragmentValue(SqlFragment fragment) implements SqlValue {
    }

    private record ParameterValue(String name, int index) implements SqlValue {
    }

    private record PlaceholderValue(String name) implements SqlValue {
    }

    private record PredicateValue(String expression) implements SqlValue {
    }

    private record StreamValue(String collection) implements SqlValue {
    }

    private record JoinerValue(String separator) implements SqlValue {
    }

    private record ForEachValue(String collection, String item, String separator) implements SqlValue {
    }

    private record CollectionItemValue(String collection) implements SqlValue {
    }

    private record MethodValue(String methodName, SqlValue receiver, List<SqlValue> args) implements SqlValue {
    }

    private record ScriptValue(SqlBuilder builder) implements SqlValue {
    }

    private static final class NullValue implements SqlValue {
        private static final NullValue INSTANCE = new NullValue();
    }

    private static final class UnknownValue implements SqlValue {
        private static final UnknownValue INSTANCE = new UnknownValue();
    }

    private static final class ArrayValue implements SqlValue {
        private final Map<Integer, SqlValue> elements = new LinkedHashMap<>();

        private ArrayValue copy(Map<String, SqlBuilder> copiedBuilders, Map<String, TextBuilder> copiedTextBuilders) {
            ArrayValue copy = new ArrayValue();
            elements.forEach((index, value) -> copy.elements.put(index,
                    copyValue(value, copiedBuilders, copiedTextBuilders)));
            return copy;
        }
    }

    private static final class ObjectValue implements SqlValue {
        private final String typeName;
        private final SqlBuilder builder;
        private final TextBuilder textBuilder;

        private ObjectValue(String typeName, SqlBuilder builder, TextBuilder textBuilder) {
            this.typeName = typeName;
            this.builder = builder;
            this.textBuilder = textBuilder;
        }

        private static ObjectValue of(String typeName) {
            return new ObjectValue(typeName, SqlBuilder.create(), TextBuilder.create());
        }

        private ObjectValue copy(Map<String, SqlBuilder> copiedBuilders, Map<String, TextBuilder> copiedTextBuilders) {
            return new ObjectValue(typeName,
                    copiedBuilders.getOrDefault(builder.id, builder.copy()),
                    copiedTextBuilders.getOrDefault(textBuilder.id, textBuilder.copy()));
        }

        private boolean isTextBuilder() {
            return isTextBuilderType(typeName);
        }
    }

    private record SqlClause(ClauseKind kind, SqlFragment fragment) {
    }

    private static final class SqlBuilder {
        private static final AtomicInteger IDS = new AtomicInteger();

        private final String id;
        private final List<SqlClause> clauses;
        private ClauseKind lastConditionKind = ClauseKind.WHERE;

        private SqlBuilder(String id, List<SqlClause> clauses) {
            this.id = id;
            this.clauses = clauses;
        }

        private static SqlBuilder create() {
            return new SqlBuilder("builder" + IDS.incrementAndGet(), new ArrayList<>());
        }

        private SqlBuilder copy() {
            SqlBuilder copy = new SqlBuilder(id, new ArrayList<>(clauses));
            copy.lastConditionKind = lastConditionKind;
            return copy;
        }

        private void add(SqlClause clause) {
            clauses.add(clause);
            if (clause.kind == ClauseKind.WHERE || clause.kind == ClauseKind.HAVING) {
                lastConditionKind = clause.kind;
            }
        }

        private List<SqlClause> clausesAfter(int size) {
            if (size >= clauses.size()) {
                return List.of();
            }
            return new ArrayList<>(clauses.subList(size, clauses.size()));
        }
    }

    private static final class TextBuilder {
        private static final AtomicInteger IDS = new AtomicInteger();

        private final String id;
        private final List<SqlFragment> fragments;

        private TextBuilder(String id, List<SqlFragment> fragments) {
            this.id = id;
            this.fragments = fragments;
        }

        private static TextBuilder create() {
            return new TextBuilder("textBuilder" + IDS.incrementAndGet(), new ArrayList<>());
        }

        private TextBuilder copy() {
            return new TextBuilder(id, new ArrayList<>(fragments));
        }

        private void add(SqlFragment fragment) {
            if (fragment instanceof ConcatFragment concatFragment) {
                fragments.addAll(concatFragment.fragments);
            } else {
                fragments.add(fragment);
            }
        }

        private List<SqlFragment> fragmentsAfter(int size) {
            if (size >= fragments.size()) {
                return List.of();
            }
            return new ArrayList<>(fragments.subList(size, fragments.size()));
        }
    }

    private static final class ExecutionState {
        private final View view;
        private final List<String> diagnostics;
        private final Map<String, SqlValue> locals = new LinkedHashMap<>();
        private final Map<String, SqlValue> fields = new LinkedHashMap<>();
        private final Map<String, SqlBuilder> builders = new LinkedHashMap<>();
        private final Map<String, TextBuilder> textBuilders = new LinkedHashMap<>();
        private final List<SqlValue> argumentValues = new ArrayList<>();
        private ObjectValue thisObject;
        private SqlValue syntheticReturn;

        private ExecutionState(View view, List<String> diagnostics) {
            this.view = view;
            this.diagnostics = diagnostics;
        }

        private ExecutionState fork() {
            ExecutionState copy = new ExecutionState(view, diagnostics);
            // 构建器按 id 复制，便于对分支内的修改做差异比较，再以动态
            // SQL 片段的形式合并回原状态。
            Map<String, SqlBuilder> copiedBuilders = builders.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().copy(),
                            (left, right) -> left, LinkedHashMap::new));
            Map<String, TextBuilder> copiedTextBuilders = textBuilders.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().copy(),
                            (left, right) -> left, LinkedHashMap::new));
            copy.builders.putAll(copiedBuilders);
            copy.textBuilders.putAll(copiedTextBuilders);
            locals.forEach((name, value) -> copy.locals.put(name,
                    copyValue(value, copiedBuilders, copiedTextBuilders)));
            fields.forEach((name, value) -> copy.fields.put(name,
                    copyValue(value, copiedBuilders, copiedTextBuilders)));
            argumentValues.forEach(value -> copy.argumentValues.add(
                    copyValue(value, copiedBuilders, copiedTextBuilders)));
            copy.thisObject = thisObject == null ? null : thisObject.copy(copiedBuilders, copiedTextBuilders);
            copy.syntheticReturn = syntheticReturn == null
                    ? null
                    : copyValue(syntheticReturn, copiedBuilders, copiedTextBuilders);
            return copy;
        }

        private Map<String, Integer> builderSizes() {
            Map<String, Integer> sizes = new LinkedHashMap<>();
            builders.forEach((id, builder) -> sizes.put(id, builder.clauses.size()));
            return sizes;
        }

        private Map<String, Integer> textBuilderSizes() {
            Map<String, Integer> sizes = new LinkedHashMap<>();
            textBuilders.forEach((id, builder) -> sizes.put(id, builder.fragments.size()));
            return sizes;
        }
    }

    private static SqlValue copyValue(
            SqlValue value,
            Map<String, SqlBuilder> copiedBuilders,
            Map<String, TextBuilder> copiedTextBuilders) {
        if (value instanceof ArrayValue arrayValue) {
            return arrayValue.copy(copiedBuilders, copiedTextBuilders);
        }
        if (value instanceof ObjectValue objectValue) {
            return objectValue.copy(copiedBuilders, copiedTextBuilders);
        }
        if (value instanceof ScriptValue scriptValue) {
            return new ScriptValue(copiedBuilders.getOrDefault(scriptValue.builder.id, scriptValue.builder.copy()));
        }
        return value;
    }

    private record ExecutionResult(boolean returned, SqlValue value) {
        private static ExecutionResult notReturned() {
            return new ExecutionResult(false, UnknownValue.INSTANCE);
        }
    }

    private record BranchPlan(
            String test,
            int thenStart,
            int thenEnd,
            int elseStart,
            int elseEnd,
            int joinIndex,
            boolean hasElse) {
    }
}
