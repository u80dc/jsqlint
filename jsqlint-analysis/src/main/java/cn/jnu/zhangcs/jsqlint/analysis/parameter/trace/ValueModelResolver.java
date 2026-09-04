package cn.jnu.zhangcs.jsqlint.analysis.parameter.trace;

import sootup.core.jimple.basic.Local;
import sootup.core.jimple.basic.Value;
import sootup.core.signatures.MethodSignature;
import sootup.core.jimple.common.constant.IntConstant;
import sootup.core.jimple.common.constant.NullConstant;
import sootup.core.jimple.common.constant.StringConstant;
import sootup.core.jimple.common.expr.*;
import sootup.core.jimple.common.ref.*;
import sootup.core.jimple.common.stmt.BranchingStmt;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JGotoStmt;
import sootup.core.jimple.common.stmt.JIfStmt;
import sootup.core.jimple.common.stmt.JIdentityStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.SootMethod;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 用于解析实际参数值的过程内解析器
 *
 * @author zhangcs
 */
public final class ValueModelResolver {

    private static final int MAX_BRANCH_DEFINITIONS = 3;
    private static final int MAX_BRANCH_NESTING = 3;

    private final SootMethod method;
    private final List<Stmt> statements;
    private final Map<String, ValueModel.ParameterValue> parameterLocals;

    /**
     * 创建绑定指定方法的解析器。
     *
     * @param method 待解析的方法，需包含方法体
     */
    public ValueModelResolver(SootMethod method) {
        this.method = method;
        this.statements = method.getBody().getStmts();
        this.parameterLocals = collectParameterLocals();
    }

    /**
     * 返回解析器绑定的方法签名，供调用方构造参数引用。
     *
     * @return 方法签名
     */
    public MethodSignature methodSignature() {
        return method.getSignature();
    }

    /**
     * 解析表达式在指定语句之前的取值模型（不含流转步骤）。
     *
     * @param value       待解析的 Jimple 表达式
     * @param beforeIndex 语句下标，仅解析该语句之前的定义
     * @return 取值模型
     */
    public ValueModel resolve(Value value, int beforeIndex) {
        return resolveWithTrace(value, beforeIndex).value();
    }

    /**
     * 解析表达式在指定语句之前的取值模型，并附带从使用点到定义点的流转步骤。
     *
     * @param value       待解析的 Jimple 表达式
     * @param beforeIndex 语句下标，仅解析该语句之前的定义
     * @return 解析结果，包含取值模型与流转步骤
     */
    ResolvedValue resolveWithTrace(Value value, int beforeIndex) {
        return resolveInternal(value, beforeIndex, new LinkedHashSet<>());
    }

    private ResolvedValue resolveInternal(Value value, int beforeIndex, Set<String> visitingLocals) {
        // 本方法只关注表达式形态。“该局部变量由哪条语句定义”这类语句敏感的
        // 问题，交由下方的方法处理。
        if (value == null) {
            return ResolvedValue.of(new ValueModel.UnknownValue("null value"));
        }
        if (value instanceof Local local) {
            return resolveLocal(local, beforeIndex, visitingLocals);
        }
        if (value instanceof StringConstant stringConstant) {
            return ResolvedValue.of(new ValueModel.LiteralValue(stringConstant.getValue(), "java.lang.String"));
        }
        if (value instanceof IntConstant intConstant) {
            return ResolvedValue.of(new ValueModel.LiteralValue(Integer.toString(intConstant.getValue()), "int"));
        }
        if (value instanceof NullConstant) {
            return ResolvedValue.of(new ValueModel.LiteralValue("null", "null"));
        }
        if (value instanceof JParameterRef parameterRef) {
            ValueModel.ParameterValue parameter = parameterValue(parameterRef.getIndex(), "param" + parameterRef.getIndex());
            return new ResolvedValue(parameter, List.of(flowStep(
                    -1,
                    null,
                    ValueFlowStep.Kind.PARAMETER_BIND,
                    "@parameter" + parameterRef.getIndex(),
                    parameter)));
        }
        if (value instanceof JArrayRef arrayRef) {
            return resolveArrayElement(arrayRef, beforeIndex, visitingLocals);
        }
        if (value instanceof JNewArrayExpr) {
            return ResolvedValue.of(new ValueModel.ArrayValue(typeName(value), Map.of()));
        }
        if (value instanceof JNewExpr) {
            return ResolvedValue.of(new ValueModel.ObjectValue(typeName(value), Map.of()));
        }
        if (value instanceof JCastExpr castExpr) {
            ResolvedValue operand = resolveInternal(castExpr.getOp(), beforeIndex, visitingLocals);
            ValueModel cast = new ValueModel.CastValue(typeName(value), operand.value());
            List<ValueFlowStep> flow = new ArrayList<>();
            flow.add(flowStep(-1, null, ValueFlowStep.Kind.CAST, value.toString(), cast));
            flow.addAll(operand.flow());
            return new ResolvedValue(cast, flow);
        }
        if (value instanceof JAddExpr addExpr) {
            ResolvedValue left = resolveInternal(addExpr.getOp1(), beforeIndex, visitingLocals);
            ResolvedValue right = resolveInternal(addExpr.getOp2(), beforeIndex, visitingLocals);
            List<ValueFlowStep> flow = new ArrayList<>(left.flow());
            flow.addAll(right.flow());
            return new ResolvedValue(concat(left.value(), right.value()), flow);
        }
        if (value instanceof JInstanceFieldRef fieldRef) {
            ResolvedValue base = resolveInternal(fieldRef.getBase(), beforeIndex, visitingLocals);
            ValueModel field = new ValueModel.FieldValue(
                    fieldRef.getFieldSignature().getName(),
                    base.value(),
                    typeName(value));
            List<ValueFlowStep> flow = new ArrayList<>();
            flow.add(flowStep(-1, null, ValueFlowStep.Kind.FIELD_READ, value.toString(), field));
            flow.addAll(base.flow());
            return new ResolvedValue(field, flow);
        }
        if (value instanceof JFieldRef fieldRef) {
            ValueModel field = new ValueModel.FieldValue(fieldRef.getFieldSignature().getName(), null, typeName(value));
            return new ResolvedValue(field, List.of(flowStep(
                    -1,
                    null,
                    ValueFlowStep.Kind.FIELD_READ,
                    value.toString(),
                    field)));
        }
        if (value instanceof AbstractInvokeExpr invoke) {
            return resolveInvoke(invoke, beforeIndex, visitingLocals);
        }

        if (value.getClass().getSimpleName().endsWith("Constant")) {
            return ResolvedValue.of(new ValueModel.LiteralValue(value.toString(), typeName(value)));
        }
        return ResolvedValue.of(new ValueModel.UnknownValue(value.toString()));
    }

    private ResolvedValue resolveLocal(Local local, int beforeIndex, Set<String> visitingLocals) {
        String name = local.getName();
        if (!visitingLocals.add(name)) {
            return ResolvedValue.of(new ValueModel.LocalValue(name, typeName(local)));
        }

        try {
            List<DefinitionSite> definitions = collectAssignmentSites(name, beforeIndex);
            if (!definitions.isEmpty()) {
                List<DefinitionSite> branchDefinitions = findBranchDefinitions(
                        beforeIndex,
                        definitions);
                if (branchDefinitions != null) {
                    List<CandidateDefinition> candidates = branchDefinitions.stream()
                            .map(definition -> resolveDefinition(definition, name, visitingLocals))
                            .toList();
                    return withBranchAlternatives(name, candidates, beforeIndex, visitingLocals);
                }

                CandidateDefinition nearest = resolveDefinition(definitions.getFirst(), name, visitingLocals);
                return withWritesAfterDefinition(
                        name,
                        nearest.value(),
                        nearest.definitionIndex(),
                        beforeIndex,
                        visitingLocals,
                        nearest.flow());
            }

            return resolveIdentityLocal(local, name, beforeIndex);
        } finally {
            visitingLocals.remove(name);
        }
    }

    private List<DefinitionSite> collectAssignmentSites(String localName, int beforeIndex) {
        int upperBound = Math.min(Math.max(beforeIndex, 0), statements.size());
        List<DefinitionSite> definitions = new ArrayList<>();
        for (int index = upperBound - 1; index >= 0; index--) {
            Stmt statement = statements.get(index);
            if (statement instanceof JAssignStmt assign
                    && assign.getLeftOp() instanceof Local left
                    && left.getName().equals(localName)) {
                definitions.add(new DefinitionSite(index, assign));
            }
        }
        return definitions;
    }

    private CandidateDefinition resolveDefinition(
            DefinitionSite definition,
            String localName,
            Set<String> visitingLocals) {

        Set<String> rhsVisitingLocals = new LinkedHashSet<>(visitingLocals);
        rhsVisitingLocals.remove(localName);
        ResolvedValue assigned = resolveInternal(
                definition.statement().getRightOp(),
                definition.index(),
                rhsVisitingLocals);
        List<ValueFlowStep> flow = new ArrayList<>();
        flow.add(flowStep(
                definition.index(),
                definition.statement(),
                ValueFlowStep.Kind.LOCAL_ASSIGN,
                localName,
                assigned.value()));
        flow.addAll(assigned.flow());
        return new CandidateDefinition(assigned.value(), flow, definition.index());
    }

    private ResolvedValue resolveIdentityLocal(Local local, String localName, int beforeIndex) {
        int upperBound = Math.min(Math.max(beforeIndex, 0), statements.size());
        for (int index = upperBound - 1; index >= 0; index--) {
            Stmt statement = statements.get(index);
            if (!(statement instanceof JIdentityStmt identity)
                    || !(identity.getLeftOp() instanceof Local left)
                    || !left.getName().equals(localName)) {
                continue;
            }
            if (identity.getRightOp() instanceof JParameterRef parameterRef) {
                ValueModel.ParameterValue parameter = parameterValue(parameterRef.getIndex(), localName);
                return new ResolvedValue(parameter, List.of(flowStep(
                        index,
                        statement,
                        ValueFlowStep.Kind.PARAMETER_BIND,
                        localName,
                        parameter)));
            }
            if (identity.getRightOp() instanceof JThisRef) {
                return ResolvedValue.of(new ValueModel.LocalValue("this", typeName(identity.getRightOp())));
            }
        }

        ValueModel.ParameterValue parameterValue = parameterLocals.get(localName);
        if (parameterValue != null) {
            return new ResolvedValue(parameterValue, List.of(flowStep(
                    -1,
                    null,
                    ValueFlowStep.Kind.PARAMETER_BIND,
                    localName,
                    parameterValue)));
        }
        return ResolvedValue.of(new ValueModel.LocalValue(localName, typeName(local)));
    }

    /**
     * 匹配有限的 if/else 菱形，不承担通用控制流或数据流分析职责。
     * 返回值按“使用点向定义点”的顺序排列；无法确认是同一二分支结果时返回 null。
     */
    private List<DefinitionSite> findBranchDefinitions(
            int beforeIndex,
            List<DefinitionSite> definitions) {

        if (definitions.size() < 2) {
            return null;
        }

        Map<Integer, DefinitionSite> definitionsByIndex = definitions.stream()
                .collect(Collectors.toMap(
                        DefinitionSite::index,
                        definition -> definition,
                        (left, right) -> left,
                        LinkedHashMap::new));
        int upperBound = Math.min(Math.max(beforeIndex, 0), statements.size());
        BranchMatch best = null;
        for (int index = 0; index < upperBound; index++) {
            if (!(statements.get(index) instanceof JIfStmt)) {
                continue;
            }
            if (isNestedIfEntry(index)) {
                continue;
            }

            BranchMatch match = matchIfElse(
                    index,
                    upperBound,
                    definitionsByIndex,
                    0);
            if (match == null) {
                continue;
            }
            if (best == null
                    || match.joinIndex() > best.joinIndex()
                    || (match.joinIndex() == best.joinIndex()
                    && match.definitions().size() > best.definitions().size())) {
                best = match;
            }
        }

        if (best == null
                || best.definitions().size() < 2
                || best.definitions().size() > MAX_BRANCH_DEFINITIONS) {
            return null;
        }
        return best.definitions().stream()
                .sorted(Comparator.comparingInt(DefinitionSite::index).reversed())
                .toList();
    }

    private BranchMatch matchIfElse(
            int ifIndex,
            int upperBound,
            Map<Integer, DefinitionSite> definitionsByIndex,
            int nestingDepth) {

        if (nestingDepth > MAX_BRANCH_NESTING
                || ifIndex < 0
                || ifIndex >= upperBound
                || !(statements.get(ifIndex) instanceof JIfStmt ifStatement)) {
            return null;
        }

        int targetIndex = branchTargetIndex(ifStatement);
        if (targetIndex <= ifIndex || targetIndex >= upperBound) {
            return null;
        }

        int gotoIndex = targetIndex - 1;
        if (!(statements.get(gotoIndex) instanceof JGotoStmt gotoStatement)) {
            return null;
        }
        int joinIndex = gotoTargetIndex(gotoStatement);
        if (joinIndex <= targetIndex || joinIndex > upperBound) {
            return null;
        }
        if (hasDefinitionAfter(joinIndex, upperBound, definitionsByIndex)) {
            return null;
        }
        if (!isCommonJoin(gotoIndex, targetIndex, joinIndex)) {
            return null;
        }
        if (hasBackwardEdge(ifIndex, upperBound)) {
            return null;
        }

        ArmMatch fallThroughArm = matchBranchArm(
                ifIndex + 1,
                gotoIndex,
                joinIndex,
                definitionsByIndex,
                nestingDepth);
        ArmMatch targetArm = matchBranchArm(
                targetIndex,
                joinIndex,
                joinIndex,
                definitionsByIndex,
                nestingDepth);
        if (fallThroughArm == null || targetArm == null) {
            return null;
        }

        List<DefinitionSite> branchDefinitions = new ArrayList<>();
        branchDefinitions.addAll(fallThroughArm.definitions());
        branchDefinitions.addAll(targetArm.definitions());
        if (branchDefinitions.size() > MAX_BRANCH_DEFINITIONS) {
            return null;
        }
        return new BranchMatch(branchDefinitions, joinIndex);
    }

    private ArmMatch matchBranchArm(
            int startIndex,
            int endIndex,
            int expectedJoinIndex,
            Map<Integer, DefinitionSite> definitionsByIndex,
            int nestingDepth) {

        if (startIndex >= endIndex || endIndex > statements.size()) {
            return null;
        }

        List<DefinitionSite> definitions = definitionsByIndex.values().stream()
                .filter(definition -> definition.index() >= startIndex && definition.index() < endIndex)
                .toList();
        boolean containsBranch = statements.subList(startIndex, endIndex).stream()
                .anyMatch(statement -> statement instanceof BranchingStmt);
        if (!containsBranch) {
            return definitions.size() == 1 ? new ArmMatch(definitions) : null;
        }

        if (!(statements.get(startIndex) instanceof JIfStmt)) {
            return null;
        }
        BranchMatch nested = matchIfElse(
                startIndex,
                endIndex,
                definitionsByIndex,
                nestingDepth + 1);
        if (nested == null || nested.joinIndex() != expectedJoinIndex) {
            return null;
        }
        Set<Integer> armDefinitionIndexes = definitions.stream()
                .map(DefinitionSite::index)
                .collect(Collectors.toSet());
        Set<Integer> nestedDefinitionIndexes = nested.definitions().stream()
                .map(DefinitionSite::index)
                .collect(Collectors.toSet());
        return armDefinitionIndexes.equals(nestedDefinitionIndexes)
                ? new ArmMatch(nested.definitions())
                : null;
    }

    private boolean hasDefinitionAfter(
            int startIndex,
            int endIndex,
            Map<Integer, DefinitionSite> definitionsByIndex) {
        return definitionsByIndex.keySet().stream()
                .anyMatch(index -> index >= startIndex && index < endIndex);
    }

    private boolean hasBackwardEdge(int startIndex, int endIndex) {
        int lowerBound = Math.max(startIndex, 0);
        int upperBound = Math.min(endIndex, statements.size());
        for (int index = lowerBound; index < upperBound; index++) {
            if (!(statements.get(index) instanceof BranchingStmt branching)) {
                continue;
            }
            try {
                for (Stmt target : branching.getTargetStmts(method.getBody())) {
                    int targetIndex = statements.indexOf(target);
                    if (targetIndex >= 0 && targetIndex <= index) {
                        return true;
                    }
                }
            } catch (RuntimeException ignored) {
                return true;
            }
        }
        return false;
    }

    private boolean isCommonJoin(int gotoIndex, int targetIndex, int joinIndex) {
        if (joinIndex < 0 || joinIndex >= statements.size()) {
            return false;
        }
        try {
            Stmt gotoStatement = statements.get(gotoIndex);
            Stmt joinStatement = statements.get(joinIndex);
            List<Stmt> predecessors = method.getBody().getStmtGraph().predecessors(joinStatement);
            boolean hasFallThroughPredecessor = predecessors.stream()
                    .anyMatch(predecessor -> predecessor == gotoStatement);
            boolean hasTargetPredecessor = predecessors.stream()
                    .mapToInt(statements::indexOf)
                    .anyMatch(index -> index >= targetIndex && index < joinIndex);
            return hasFallThroughPredecessor && hasTargetPredecessor;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isNestedIfEntry(int ifIndex) {
        for (int index = 0; index < ifIndex; index++) {
            if (!(statements.get(index) instanceof JIfStmt ifStatement)) {
                continue;
            }
            if (branchTargetIndex(ifStatement) == ifIndex) {
                return true;
            }
        }
        return false;
    }

    private int branchTargetIndex(JIfStmt ifStatement) {
        try {
            List<Stmt> targets = ifStatement.getTargetStmts(method.getBody());
            return targets.isEmpty() ? -1 : statements.indexOf(targets.getFirst());
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private int gotoTargetIndex(JGotoStmt gotoStatement) {
        try {
            List<Stmt> targets = gotoStatement.getTargetStmts(method.getBody());
            return targets.isEmpty() ? -1 : statements.indexOf(targets.getFirst());
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    /**
     * 将同一变量的多个到达定义折叠为单一结果。
     *
     * <p>单一定义时保持原有语义（含数组/对象/StringBuilder 的定义后写补齐）；
     * 多个定义时取每个定义的值模型构成 {@link ValueModel.MultiValue}，值流合并各定义的流步骤。
     * 候选去重后仅剩一个等价定义时同样退化为单值，避免无意义的集合包装。</p>
     */
    private ResolvedValue withBranchAlternatives(
            String name,
            List<CandidateDefinition> candidates,
            int beforeIndex,
            Set<String> visitingLocals) {

        if (candidates.size() == 1) {
            CandidateDefinition only = candidates.getFirst();
            return withWritesAfterDefinition(
                    name,
                    only.value(),
                    only.definitionIndex(),
                    beforeIndex,
                    visitingLocals,
                    only.flow());
        }

        // 候选按语句位置降序（回溯序已保证）排列；去重等价定义
        List<ValueModel> alternatives = new ArrayList<>();
        List<ValueFlowStep> mergedFlow = new ArrayList<>();
        for (CandidateDefinition candidate : candidates) {
            boolean duplicated = alternatives.stream()
                    .anyMatch(existing -> existing.display().equals(candidate.value().display()));
            if (!duplicated) {
                alternatives.add(candidate.value());
            }
            mergedFlow.addAll(candidate.flow());
        }

        if (alternatives.size() == 1) {
            CandidateDefinition representative = candidates.getFirst();
            return withWritesAfterDefinition(
                    name,
                    alternatives.getFirst(),
                    representative.definitionIndex(),
                    beforeIndex,
                    visitingLocals,
                    mergedFlow);
        }

        return new ResolvedValue(
                new ValueModel.MultiValue(alternatives),
                mergedFlow);
    }

    /**
     * 一个到达定义：值模型 + 该定义处的值流 + 定义语句位置。
     */
    private record CandidateDefinition(
            ValueModel value,
            List<ValueFlowStep> flow,
            int definitionIndex) {
    }

    private record DefinitionSite(int index, JAssignStmt statement) {
    }

    private record ArmMatch(List<DefinitionSite> definitions) {

        private ArmMatch {
            definitions = List.copyOf(definitions);
        }
    }

    private record BranchMatch(List<DefinitionSite> definitions, int joinIndex) {

        private BranchMatch {
            definitions = List.copyOf(definitions);
        }
    }

    private ResolvedValue withWritesAfterDefinition(
            String localName,
            ValueModel assigned,
            int definitionIndex,
            int beforeIndex,
            Set<String> visitingLocals,
            List<ValueFlowStep> baseFlow) {

        if (assigned instanceof ValueModel.ArrayValue arrayValue) {
            return withArrayWritesAfterDefinition(localName, arrayValue, definitionIndex, beforeIndex,
                    visitingLocals, baseFlow);
        }
        if (assigned instanceof ValueModel.ObjectValue objectValue && isStringBuilderType(objectValue.type())) {
            return withStringBuilderWritesAfterDefinition(localName, objectValue, definitionIndex, beforeIndex,
                    visitingLocals, baseFlow);
        }
        if (assigned instanceof ValueModel.ObjectValue objectValue) {
            return withObjectWritesAfterDefinition(localName, objectValue, definitionIndex, beforeIndex,
                    visitingLocals, baseFlow);
        }
        return new ResolvedValue(assigned, baseFlow);
    }

    private ResolvedValue withArrayWritesAfterDefinition(
            String localName,
            ValueModel.ArrayValue arrayValue,
            int definitionIndex,
            int beforeIndex,
            Set<String> visitingLocals,
            List<ValueFlowStep> baseFlow) {

        // 数组创建与元素写入是两条独立的 Jimple 语句：
        // a = new Object[1]; a[0] = id; call(a)。由于路径顺序从使用点指向定义点，
        // 因此在基础数组赋值之前记录写入。
        Map<Integer, ValueModel> elements = new LinkedHashMap<>(arrayValue.elements());
        List<ValueFlowStep> flow = new ArrayList<>();
        Set<Integer> seenIndexes = new LinkedHashSet<>();
        for (int i = Math.min(beforeIndex - 1, statements.size() - 1); i > definitionIndex; i--) {
            Stmt stmt = statements.get(i);
            if (!(stmt instanceof JAssignStmt assign) || !(assign.getLeftOp() instanceof JArrayRef arrayRef)) {
                continue;
            }
            if (!arrayRef.getBase().getName().equals(localName)) {
                continue;
            }
            Integer index = intValue(arrayRef.getIndex());
            if (index == null || !seenIndexes.add(index)) {
                continue;
            }

            ResolvedValue element = resolveInternal(assign.getRightOp(), i, new LinkedHashSet<>(visitingLocals));
            elements.put(index, element.value());
            flow.add(flowStep(i, stmt, ValueFlowStep.Kind.ARRAY_WRITE, arrayRef.toString(), element.value()));
            flow.addAll(element.flow());
        }
        flow.addAll(baseFlow);
        return new ResolvedValue(new ValueModel.ArrayValue(arrayValue.type(), elements), flow);
    }

    private ResolvedValue withStringBuilderWritesAfterDefinition(
            String localName,
            ValueModel.ObjectValue objectValue,
            int definitionIndex,
            int beforeIndex,
            Set<String> visitingLocals,
            List<ValueFlowStep> baseFlow) {

        List<ValueModel> parts = new ArrayList<>();
        List<ValueFlowStep> flow = new ArrayList<>();
        for (int i = definitionIndex + 1; i < beforeIndex && i < statements.size(); i++) {
            Stmt stmt = statements.get(i);
            AbstractInstanceInvokeExpr invoke = instanceInvokeOn(stmt, localName);
            if (invoke == null || !isStringBuilderType(invoke.getMethodSignature()
                    .getDeclClassType()
                    .getFullyQualifiedName())) {
                continue;
            }

            String methodName = invoke.getMethodSignature().getName();
            if ("<init>".equals(methodName)) {
                if (invoke.getArgCount() > 0) {
                    ResolvedValue argument = resolveInternal(
                            invoke.getArg(0),
                            i,
                            new LinkedHashSet<>(visitingLocals));
                    parts.add(argument.value());
                    flow.add(flowStep(i, stmt, ValueFlowStep.Kind.METHOD_INVOKE, invoke.toString(), argument.value()));
                    flow.addAll(argument.flow());
                }
                continue;
            }
            if ("append".equals(methodName) && invoke.getArgCount() > 0) {
                ResolvedValue argument = resolveInternal(
                        invoke.getArg(0),
                        i,
                        new LinkedHashSet<>(visitingLocals));
                parts.add(argument.value());
                flow.add(flowStep(i, stmt, ValueFlowStep.Kind.METHOD_INVOKE, invoke.toString(), argument.value()));
                flow.addAll(argument.flow());
            }
        }

        if (parts.isEmpty()) {
            return new ResolvedValue(objectValue, baseFlow);
        }

        flow.addAll(baseFlow);
        return new ResolvedValue(new ValueModel.ConcatValue(parts), flow);
    }

    private ResolvedValue withObjectWritesAfterDefinition(
            String localName,
            ValueModel.ObjectValue objectValue,
            int definitionIndex,
            int beforeIndex,
            Set<String> visitingLocals,
            List<ValueFlowStep> baseFlow) {

        Map<String, ValueModel> fields = new LinkedHashMap<>(objectValue.fields());
        List<ValueFlowStep> flow = new ArrayList<>();
        Set<String> seenFields = new LinkedHashSet<>();
        for (int i = Math.min(beforeIndex - 1, statements.size() - 1); i > definitionIndex; i--) {
            Stmt stmt = statements.get(i);
            AbstractInstanceInvokeExpr setter = setterInvokeOn(stmt, localName);
            if (setter == null) {
                continue;
            }

            String fieldName = setterPropertyName(setter.getMethodSignature().getName());
            if (fieldName == null || !seenFields.add(fieldName)) {
                continue;
            }

            ResolvedValue fieldValue = resolveInternal(setter.getArg(0), i, new LinkedHashSet<>(visitingLocals));
            fields.put(fieldName, fieldValue.value());
            flow.add(flowStep(i, stmt, ValueFlowStep.Kind.OBJECT_FIELD_WRITE, fieldName, fieldValue.value()));
            flow.addAll(fieldValue.flow());
        }
        flow.addAll(baseFlow);
        return new ResolvedValue(new ValueModel.ObjectValue(objectValue.type(), fields), flow);
    }

    private AbstractInstanceInvokeExpr setterInvokeOn(Stmt stmt, String localName) {
        AbstractInstanceInvokeExpr instanceInvoke = instanceInvokeOn(stmt, localName);
        if (instanceInvoke == null) {
            return null;
        }
        String methodName = instanceInvoke.getMethodSignature().getName();
        if (setterPropertyName(methodName) == null || instanceInvoke.getArgCount() != 1) {
            return null;
        }
        return instanceInvoke;
    }

    private AbstractInstanceInvokeExpr instanceInvokeOn(Stmt stmt, String localName) {
        AbstractInvokeExpr invoke = null;
        if (stmt instanceof JAssignStmt assign) {
            invoke = assign.getInvokeExpr().orElse(null);
        } else if (stmt instanceof sootup.core.jimple.common.stmt.JInvokeStmt invokeStmt) {
            invoke = invokeStmt.getInvokeExpr().orElse(null);
        }
        if (!(invoke instanceof AbstractInstanceInvokeExpr instanceInvoke)
                || !(instanceInvoke.getBase() instanceof Local base)
                || !base.getName().equals(localName)) {
            return null;
        }
        return instanceInvoke;
    }

    private boolean isStringBuilderType(String type) {
        return "java.lang.StringBuilder".equals(type) || "java.lang.StringBuffer".equals(type);
    }

    private String setterPropertyName(String methodName) {
        if (methodName == null || !methodName.startsWith("set") || methodName.length() <= 3) {
            return null;
        }
        char first = methodName.charAt(3);
        if (!Character.isUpperCase(first)) {
            return null;
        }
        return Character.toLowerCase(first) + methodName.substring(4);
    }

    private ResolvedValue resolveArrayElement(JArrayRef arrayRef, int beforeIndex, Set<String> visitingLocals) {
        // 直接数组读取仅在数组基址已折叠为带已知整数下标的 ArrayValue 时才解析。
        Integer index = intValue(arrayRef.getIndex());
        ResolvedValue base = resolveInternal(arrayRef.getBase(), beforeIndex, visitingLocals);
        ValueModel value = new ValueModel.UnknownValue(arrayRef.toString());
        if (base.value() instanceof ValueModel.ArrayValue arrayValue && index != null) {
            value = arrayValue.elements().getOrDefault(index, value);
        }

        List<ValueFlowStep> flow = new ArrayList<>();
        flow.add(flowStep(-1, null, ValueFlowStep.Kind.ARRAY_READ, arrayRef.toString(), value));
        flow.addAll(base.flow());
        return new ResolvedValue(value, flow);
    }

    private ResolvedValue resolveInvoke(AbstractInvokeExpr invoke, int beforeIndex, Set<String> visitingLocals) {
        if (invoke instanceof JDynamicInvokeExpr dynamicInvoke && isStringConcatDynamicInvoke(dynamicInvoke)) {
            return resolveStringConcatDynamicInvoke(dynamicInvoke, beforeIndex, visitingLocals);
        }

        // 方法调用保留为 InvokeValue 节点。解析器记录其接收者与参数，
        // 但不在 p1 阶段推断返回值。
        ValueModel receiver = null;
        List<ValueFlowStep> flow = new ArrayList<>();
        if (invoke instanceof AbstractInstanceInvokeExpr instanceInvoke) {
            ResolvedValue resolvedReceiver = resolveInternal(
                    instanceInvoke.getBase(),
                    beforeIndex,
                    new LinkedHashSet<>(visitingLocals));
            receiver = resolvedReceiver.value();
            flow.addAll(resolvedReceiver.flow());
        }

        List<ValueModel> arguments = new ArrayList<>();
        List<ValueFlowStep> argumentFlow = new ArrayList<>();
        for (int i = 0; i < invoke.getArgCount(); i++) {
            ResolvedValue argument = resolveInternal(invoke.getArg(i), beforeIndex, new LinkedHashSet<>(visitingLocals));
            arguments.add(argument.value());
            argumentFlow.addAll(argument.flow());
        }

        ValueModel invokeValue = new ValueModel.InvokeValue(invoke.getMethodSignature(), receiver, arguments);
        List<ValueFlowStep> orderedFlow = new ArrayList<>();
        orderedFlow.add(flowStep(-1, null, ValueFlowStep.Kind.METHOD_INVOKE, invoke.toString(), invokeValue));
        orderedFlow.addAll(flow);
        orderedFlow.addAll(argumentFlow);
        return new ResolvedValue(invokeValue, orderedFlow);
    }

    private boolean isStringConcatDynamicInvoke(JDynamicInvokeExpr invoke) {
        String bootstrapClass = invoke.getBootstrapMethodSignature()
                .getDeclClassType()
                .getFullyQualifiedName();
        String bootstrapName = invoke.getBootstrapMethodSignature().getName();
        String methodName = invoke.getMethodSignature().getName();
        return "java.lang.invoke.StringConcatFactory".equals(bootstrapClass)
                && (startsWithMakeConcat(bootstrapName) || startsWithMakeConcat(methodName));
    }

    private boolean startsWithMakeConcat(String methodName) {
        return methodName != null && methodName.startsWith("makeConcat");
    }

    private ResolvedValue resolveStringConcatDynamicInvoke(
            JDynamicInvokeExpr invoke,
            int beforeIndex,
            Set<String> visitingLocals) {

        List<ResolvedValue> arguments = new ArrayList<>();
        for (int i = 0; i < invoke.getArgCount(); i++) {
            arguments.add(resolveInternal(invoke.getArg(i), beforeIndex, new LinkedHashSet<>(visitingLocals)));
        }

        String recipe = concatRecipe(invoke);
        List<ValueModel> parts = new ArrayList<>();
        if (recipe == null) {
            arguments.stream().map(ResolvedValue::value).forEach(parts::add);
        } else {
            int argumentIndex = 0;
            int constantIndex = 1;
            StringBuilder literal = new StringBuilder();
            for (int i = 0; i < recipe.length(); i++) {
                char ch = recipe.charAt(i);
                if (ch == '\u0001') {
                    flushLiteral(parts, literal);
                    if (argumentIndex < arguments.size()) {
                        parts.add(arguments.get(argumentIndex).value());
                    } else {
                        parts.add(new ValueModel.UnknownValue("missing string concat argument"));
                    }
                    argumentIndex++;
                } else if (ch == '\u0002') {
                    flushLiteral(parts, literal);
                    parts.add(bootstrapConstant(invoke, constantIndex++));
                } else {
                    literal.append(ch);
                }
            }
            flushLiteral(parts, literal);
            while (argumentIndex < arguments.size()) {
                parts.add(arguments.get(argumentIndex++).value());
            }
        }

        ValueModel concatValue = new ValueModel.ConcatValue(parts);
        List<ValueFlowStep> flow = new ArrayList<>();
        flow.add(flowStep(-1, null, ValueFlowStep.Kind.METHOD_INVOKE, invoke.toString(), concatValue));
        for (ResolvedValue argument : arguments) {
            flow.addAll(argument.flow());
        }
        return new ResolvedValue(concatValue, flow);
    }

    private String concatRecipe(JDynamicInvokeExpr invoke) {
        if (invoke.getBootstrapArgCount() == 0) {
            return null;
        }
        Value recipe = invoke.getBootstrapArg(0);
        if (recipe instanceof StringConstant stringConstant) {
            return stringConstant.getValue();
        }
        return null;
    }

    private ValueModel bootstrapConstant(JDynamicInvokeExpr invoke, int index) {
        if (index >= invoke.getBootstrapArgCount()) {
            return new ValueModel.UnknownValue("missing string concat constant");
        }
        Value value = invoke.getBootstrapArg(index);
        if (value instanceof StringConstant stringConstant) {
            return new ValueModel.LiteralValue(stringConstant.getValue(), "java.lang.String");
        }
        return new ValueModel.LiteralValue(value.toString(), typeName(value));
    }

    private void flushLiteral(List<ValueModel> parts, StringBuilder literal) {
        if (!literal.isEmpty()) {
            parts.add(new ValueModel.LiteralValue(literal.toString(), "java.lang.String"));
            literal.setLength(0);
        }
    }

    private ValueModel concat(ValueModel left, ValueModel right) {
        List<ValueModel> parts = new ArrayList<>();
        if (left instanceof ValueModel.ConcatValue concatValue) {
            parts.addAll(concatValue.parts());
        } else {
            parts.add(left);
        }
        if (right instanceof ValueModel.ConcatValue concatValue) {
            parts.addAll(concatValue.parts());
        } else {
            parts.add(right);
        }
        return new ValueModel.ConcatValue(parts);
    }

    private Map<String, ValueModel.ParameterValue> collectParameterLocals() {
        Map<String, ValueModel.ParameterValue> result = new LinkedHashMap<>();
        for (Stmt stmt : statements) {
            if (!(stmt instanceof JIdentityStmt identity) || !(identity.getLeftOp() instanceof Local left)) {
                continue;
            }
            if (identity.getRightOp() instanceof JParameterRef parameterRef) {
                // 保留调试元数据生成的局部变量名（若有意义）；
                // 否则 ParameterValue 回退为 paramN。
                result.put(left.getName(), parameterValue(parameterRef.getIndex(), left.getName()));
            }
        }
        return result;
    }

    private ValueFlowStep flowStep(
            int statementIndex,
            Stmt statement,
            ValueFlowStep.Kind kind,
            String subject,
            ValueModel value) {

        return new ValueFlowStep(
                method.getSignature(),
                statementIndex,
                statement == null ? "" : statement.toString(),
                kind,
                subject,
                value);
    }

    private ValueModel.ParameterValue parameterValue(int index, String localName) {
        return new ValueModel.ParameterValue(
                index,
                parameterName(localName, index),
                MethodParameterRef.parameterType(method.getSignature(), index));
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

    private String typeName(Value value) {
        try {
            return value.getType().toString();
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private String parameterName(String localName, int index) {
        String cleaned = cleanName(localName);
        if (cleaned.isBlank() || isSyntheticLocalName(cleaned)) {
            return "param" + index;
        }
        return cleaned;
    }

    private boolean isSyntheticLocalName(String name) {
        return name.matches("[a-z]\\d+")
                || name.startsWith("$stack")
                || name.startsWith("stack");
    }

    private String cleanName(String name) {
        if (name == null) {
            return "";
        }
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(0, colon).trim() : name.trim();
    }

    /**
     * 解析结果：取值模型及其流转步骤。
     *
     * @param value 取值模型
     * @param flow  从使用点到定义点的流转步骤
     */
    record ResolvedValue(ValueModel value, List<ValueFlowStep> flow) {

        ResolvedValue {
            Objects.requireNonNull(value, "value");
            flow = flow == null ? List.of() : List.copyOf(flow);
        }

        /**
         * 创建不带流转步骤的解析结果。
         *
         * @param value 取值模型
         * @return 解析结果
         */
        static ResolvedValue of(ValueModel value) {
            return new ResolvedValue(value, List.of());
        }
    }
}
