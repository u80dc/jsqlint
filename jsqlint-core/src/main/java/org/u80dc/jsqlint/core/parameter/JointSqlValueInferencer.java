package org.u80dc.jsqlint.core.parameter;

import org.u80dc.jsqlint.core.*;
import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.node.SqlValue;
import java.util.*;

import static org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult.*;

/** 通用联合分析入口：先组合来源路径，再求解控制条件，最后用共享入口模型重演每个参数。 @author u80dc */
public final class JointSqlValueInferencer {
    private static final int MAX_COMBINATIONS = 128, MAX_ATTEMPTS = 512, MAX_PREFIX_CHECKS = 4096;
    private final DataFlowAnalysis flow;
    private final JointPathExplorer explorer;
    private final PathPredicateSolver solver;

    public JointSqlValueInferencer(DataFlowAnalysis flow) {
        this.flow = Objects.requireNonNull(flow); explorer = new JointPathExplorer(flow); solver = new PathPredicateSolver(flow::checkpoint);
    }

    public RawSql infer(RawSql raw, List<SqlExtraction.Binding> bindings, FlowPoint execution) {
        return infer(raw, bindings, execution, List.of(), List.of());
    }

    /** controlFlowPaths 是产生框架快照的路径析取；不提供时仅按变量来源重建控制路径。 */
    public RawSql infer(RawSql raw, List<SqlExtraction.Binding> bindings, FlowPoint execution,
                        List<List<ControlFlowEdge>> controlFlowPaths, List<String> snapshotIssues) {
        List<RawSql.Variant> variants = new ArrayList<>();
        for (var variant : raw.variants()) {
            flow.checkpoint();
            JointSqlInferenceResult result = infer(variant, bindings, execution, controlFlowPaths, snapshotIssues);
            variants.add(new RawSql.Variant(variant.text(), variant.placeholders(), variant.trace(), bindings, Optional.of(result)));
        }
        return new RawSql(variants, raw.issues());
    }

    private record Dimension(String name, int binding, boolean key, List<InferredParameterValue> values) {}
    private JointSqlInferenceResult infer(RawSql.Variant variant, List<SqlExtraction.Binding> bindings, FlowPoint execution,
                                         List<List<ControlFlowEdge>> histories, List<String> snapshotIssues) {
        List<Dimension> dimensions = new ArrayList<>();
        Set<String> issues = new LinkedHashSet<>(snapshotIssues);
        for (var parameter : variant.parameters()) {
            dimensions.add(new Dimension(parameter.name(), -1, false, parameter.possibleValues())); addLimits(issues, parameter.issues());
        }
        var keyInferencer = new ParameterValueInferencer(new RandomExternalValueGenerator(), flow::checkpoint);
        for (int i = 0; i < bindings.size(); i++) {
            var binding = bindings.get(i);
            var keys = keyInferencer.infer(binding.keyTrace());
            dimensions.add(new Dimension(binding.key(), i, true, keys.possibleValues())); addLimits(issues, keys.issues());
            dimensions.add(new Dimension(binding.key(), i, false, binding.inference().possibleValues())); addLimits(issues, binding.inference().issues());
        }
        if (dimensions.size() > 64 || dimensions.stream().anyMatch(d -> d.values().isEmpty()))
            return new JointSqlInferenceResult(Status.UNKNOWN, List.of(), List.of("Joint parameters unavailable or dimension limit exceeded"));
        var query = new Query(variant, bindings, execution, dimensions, issues,
                JointControlFlowHistories.simplify(histories, flow::checkpoint), snapshotIssues);
        query.enumerate(0, new ArrayList<>());
        Status status = !issues.isEmpty() || query.results.stream().anyMatch(value -> value.status() == Status.UNKNOWN)
                ? Status.UNKNOWN : query.results.isEmpty() ? Status.UNSATISFIABLE : Status.SATISFIABLE;
        return new JointSqlInferenceResult(status, query.results, new ArrayList<>(issues));
    }

    private static void addLimits(Set<String> issues, List<String> source) {
        // 原随机样例的重演异常可能被路径模型修正；只有已遗漏候选的截断必须继续影响全局状态。
        source.stream().filter(issue -> issue.contains("limit") || issue.contains("budget")).forEach(issues::add);
    }

    private final class Query {
        final RawSql.Variant variant;
        final List<SqlExtraction.Binding> bindings;
        final FlowPoint execution;
        final List<Dimension> dimensions;
        final Set<String> issues;
        final List<List<ControlFlowEdge>> histories;
        final List<String> snapshotIssues;
        final List<Combination> results = new ArrayList<>();
        int attempts, prefixChecks;
        boolean stopped;
        Query(RawSql.Variant variant, List<SqlExtraction.Binding> bindings, FlowPoint execution,
              List<Dimension> dimensions, Set<String> issues, List<List<ControlFlowEdge>> histories, List<String> snapshotIssues) {
            this.variant = variant; this.bindings = bindings; this.execution = execution; this.dimensions = dimensions; this.issues = issues;
            this.histories = histories; this.snapshotIssues = snapshotIssues;
        }
        void enumerate(int index, List<InferredParameterValue> selected) {
            flow.checkpoint();
            if (stopped) return;
            if (attempts >= MAX_ATTEMPTS || results.size() >= MAX_COMBINATIONS) {
                issues.add("Joint parameter combination limit exceeded"); return;
            }
            if (index == dimensions.size()) {
                for (var history : histories) {
                    if (attempts++ >= MAX_ATTEMPTS || results.size() >= MAX_COMBINATIONS) {
                        issues.add("Joint parameter combination limit exceeded"); break;
                    }
                    evaluate(selected, history);
                }
                return;
            }
            for (var value : dimensions.get(index).values()) {
                selected.add(value);
                // 先检查部分参数来源能否共存，避免等到全部参数的笛卡尔积形成后才剔除冲突。
                if (index + 1 == dimensions.size() || dimensions.get(index).values().size() == 1 || compatible(selected))
                    enumerate(index + 1, selected);
                selected.removeLast();
                if (stopped) break;
                if (attempts >= MAX_ATTEMPTS || results.size() >= MAX_COMBINATIONS) {
                    issues.add("Joint parameter combination limit exceeded"); break;
                }
            }
        }

        boolean compatible(List<InferredParameterValue> selected) {
            if (++prefixChecks > MAX_PREFIX_CHECKS) {
                issues.add("Joint parameter prefix combination limit exceeded"); stopped = true; return false;
            }
            for (var history : histories) {
                var paths = explorer.explore(execution, roots(selected), history);
                // 部分路径未知时不能剪掉这个前缀，最终求解仍会记录完整诊断。
                if (!paths.issues().isEmpty() || paths.selections().stream().anyMatch(path -> !solver.contradicts(path.conditions()))) return true;
            }
            return false;
        }

        List<ValueTrace.Node> roots(List<InferredParameterValue> selected) {
            List<ValueTrace.Node> roots = new ArrayList<>();
            variant.trace().ifPresent(trace -> roots.add(trace.root()));
            selected.forEach(value -> roots.add(value.propagationPath().root()));
            return roots;
        }

        void evaluate(List<InferredParameterValue> selected, List<ControlFlowEdge> history) {
            var paths = explorer.explore(execution, roots(selected), history);
            for (var path : paths.selections()) {
                flow.checkpoint();
                if (results.size() >= MAX_COMBINATIONS) { issues.add("Joint parameter combination limit exceeded"); break; }
                var solution = solver.solve(path.conditions());
                if (solution.status() == Status.UNSATISFIABLE) continue;
                var replay = new ParameterValueInferencer(new RandomExternalValueGenerator(), flow::checkpoint, solution.inputs());
                List<InferredParameterValue> values = new ArrayList<>();
                Set<String> localIssues = new LinkedHashSet<>(solution.issues());
                localIssues.addAll(snapshotIssues);
                for (var value : selected) {
                    ValueTrace trace = explorer.select(value.propagationPath(), path);
                    var inferred = replay.infer(trace);
                    localIssues.addAll(inferred.issues());
                    if (inferred.possibleValues().size() != 1) {
                        localIssues.add("Joint replay did not resolve a unique selected value"); values.add(value);
                    } else values.add(inferred.possibleValues().getFirst());
                }
                if (values.stream().anyMatch(value -> !value.complete())) localIssues.add("Joint replay contains unknown parameter values");
                Status status = solution.status() == Status.SATISFIABLE && localIssues.isEmpty() ? Status.SATISFIABLE : Status.UNKNOWN;
                add(values, path.conditions(), solution.inputs(), status, new ArrayList<>(localIssues));
            }
            if (!paths.issues().isEmpty()) {
                issues.addAll(paths.issues());
                // 未覆盖的路径必须显式保留未知候选，不能因只分析了正常无环路径而漏报。
                if (results.size() < MAX_COMBINATIONS) add(selected, List.of(), Map.of(), Status.UNKNOWN, paths.issues());
            }
        }
        void add(List<InferredParameterValue> values, List<Condition> conditions, Map<ValueTrace.Variable, SqlValue> model,
                 Status status, List<String> localIssues) {
            Map<String, InferredParameterValue> parameters = new LinkedHashMap<>();
            Map<Integer, InferredParameterValue> keys = new HashMap<>(), bound = new HashMap<>();
            Map<ValueTrace.Variable, SqlValue> inputs = new LinkedHashMap<>(model);
            for (int i = 0; i < dimensions.size(); i++) {
                Dimension dimension = dimensions.get(i); InferredParameterValue value = values.get(i);
                if (dimension.binding() < 0) parameters.put(dimension.name(), value);
                else if (dimension.key()) keys.put(dimension.binding(), value);
                else bound.put(dimension.binding(), value);
                value.initialValues().stream().filter(InferredParameterValue.InitialValue::generated)
                        .forEach(input -> inputs.putIfAbsent(input.variable(), input.value()));
            }
            List<BindingValue> bindingValues = new ArrayList<>();
            for (int i = 0; i < bindings.size(); i++) bindingValues.add(new BindingValue(bindings.get(i).key(), keys.get(i), bound.get(i)));
            var combination = new Combination(status, parameters, bindingValues, conditions, inputs, localIssues);
            if (!results.contains(combination)) results.add(combination);
        }
    }
}
