package org.u80dc.jsqlint.core.parameter;

import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.spi.*;
import sootup.core.jimple.common.Local;
import sootup.core.jimple.common.stmt.*;
import sootup.core.jimple.javabytecode.stmt.JSwitchStmt;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;
import java.util.*;

import static org.u80dc.jsqlint.core.dataflow.ValueTrace.*;
import static org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult.Condition;

/** 按调用帧检查一组传播链是否能出现在同一条控制流路径上。循环、异常边和预算截断保守返回未知。 @author u80dc */
final class JointPathExplorer {
    private final int maxPaths, maxSteps, maxDependencyDepth, maxControlFlowDepth;
    record Frame(MethodSignature method, List<SqlCallSite> context) {
        Frame { context = List.copyOf(context); }
        static Frame of(Variable variable) { return new Frame(variable.method(), variable.callContext()); }
    }
    record Use(int statement, String local) {}
    record Requirement(Frame frame, Set<Integer> statements, Map<Use, Integer> definitions,
                       Map<Integer, Integer> successors, Set<Integer> returns) {}
    record Edge(int statement, int successor) {}
    record Path(Frame frame, List<Integer> order, Map<Use, Integer> definitions, List<Edge> branches) {}
    record Paths(List<Path> values, List<String> issues) {}
    /** 声明签名不是实际分派目标；同一调用位置在不同调用上下文中仍可独立选择。 */
    record DispatchKey(Frame caller, SqlCallSite call) {}
    record Selection(Map<Frame, Path> paths, List<Condition> conditions,
                     Map<DispatchKey, Optional<MethodSignature>> dispatches) {}
    record Result(List<Selection> selections, List<String> issues) {}
    private final DataFlowAnalysis flow;
    private final PathPredicateSolver solver;
    private final Map<Requirement, Paths> cache = new LinkedHashMap<>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Requirement, Paths> entry) { return size() > 256; }
    };
    private final Map<SqlCallSite, InvocationSite> calls = new HashMap<>();
    private record ConditionKey(Frame frame, int statement) {}
    private final Map<ConditionKey, ValueTrace> conditionCache = new LinkedHashMap<>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<ConditionKey, ValueTrace> entry) { return size() > 1024; }
    };

    /** 预算来自 AnalyzerConfig 的联合求解配置；路径与步数分别对应每帧保留的候选与处理的工作量。 */
    JointPathExplorer(DataFlowAnalysis flow, int maxPaths, int maxSteps, int modelSteps) {
        this(flow, maxPaths, maxSteps, modelSteps, 256, 512);
    }

    JointPathExplorer(DataFlowAnalysis flow, int maxPaths, int maxSteps, int modelSteps,
                      int maxDependencyDepth, int maxControlFlowDepth) {
        this(flow, maxPaths, maxSteps, modelSteps, maxDependencyDepth, maxControlFlowDepth, 1_000_000);
    }

    JointPathExplorer(DataFlowAnalysis flow, int maxPaths, int maxSteps, int modelSteps,
                      int maxDependencyDepth, int maxControlFlowDepth, int maxLength) {
        if (maxPaths < 1 || maxSteps < 1 || modelSteps < 1 || maxDependencyDepth < 1 || maxControlFlowDepth < 1 || maxLength < 1)
            throw new IllegalArgumentException("Joint path budgets must be positive");
        this.flow = flow; this.maxPaths = maxPaths; this.maxSteps = maxSteps;
        this.maxDependencyDepth = maxDependencyDepth; this.maxControlFlowDepth = maxControlFlowDepth;
        solver = new PathPredicateSolver(flow::checkpoint, modelSteps, maxDependencyDepth, maxLength);
    }

    Result explore(FlowPoint execution, List<Node> roots, List<ControlFlowEdge> history) {
        if (history.stream().anyMatch(ControlFlowEdge::exceptional))
            return new Result(List.of(), List.of("Joint snapshot includes exceptional control flow"));
        Map<Frame, Set<Integer>> required = new LinkedHashMap<>();
        Map<Frame, Map<Use, Integer>> definitions = new HashMap<>();
        Set<String> issues = new LinkedHashSet<>();
        boolean[] conflict = {false};
        Map<DispatchKey, Optional<MethodSignature>> dispatches = new LinkedHashMap<>();
        Map<Frame, Map<Integer, Integer>> successors = new HashMap<>();
        add(execution.value(sootup.core.jimple.common.constant.IntConstant.getInstance(0)).snapshot(flow.program()), required);
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Node root : roots) collect(root, required, definitions, seen, conflict, issues, dispatches, successors);
        for (ControlFlowEdge edge : history) {
            var from = edge.from().value(sootup.core.jimple.common.constant.IntConstant.getInstance(0)).snapshot(flow.program());
            var to = edge.to().value(sootup.core.jimple.common.constant.IntConstant.getInstance(0)).snapshot(flow.program());
            add(from, required); add(to, required);
            var selected = successors.computeIfAbsent(Frame.of(from), ignored -> new HashMap<>());
            Integer old = selected.putIfAbsent(from.statementIndex(), to.statementIndex());
            if (old != null && old != to.statementIndex()) conflict[0] = true;
        }
        if (conflict[0]) return new Result(List.of(), List.copyOf(issues));
        for (Frame frame : required.keySet()) if (!chooseFrame(dispatches, frame))
            return new Result(List.of(), List.copyOf(issues));
        // 某些输入只在最内层用到，调用它的每一层也必须实际经过该调用语句。
        for (Frame frame : List.copyOf(required.keySet())) {
            for (int i = 0; i < frame.context().size(); i++) {
                InvocationSite call = call(frame.context().get(i));
                var owner = new Frame(call.method().getSignature(), frame.context().subList(0, i));
                required.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(call.statementIndex());
            }
        }
        Map<Frame, Requirement> requirements = new LinkedHashMap<>();
        for (var entry : required.entrySet()) {
            requirements.put(entry.getKey(), new Requirement(entry.getKey(), Set.copyOf(entry.getValue()),
                    Map.copyOf(definitions.getOrDefault(entry.getKey(), Map.of())),
                    Map.copyOf(successors.getOrDefault(entry.getKey(), Map.of())), Set.of()));
        }
        return complete(requirements, issues, dispatches);
    }

    private record Search(Map<Frame, Requirement> requirements, Map<Frame, Path> paths,
                          Map<DispatchKey, Optional<MethodSignature>> dispatches) {}

    /** 条件可引入新的被调用方法，按需扩展到依赖闭包；不同分派目标和返回语句始终按析取处理。 */
    private Result complete(Map<Frame, Requirement> requirements, Set<String> issues,
                            Map<DispatchKey, Optional<MethodSignature>> dispatches) {
        Deque<Search> pending = new ArrayDeque<>();
        pending.add(new Search(requirements, Map.of(), Map.copyOf(dispatches)));
        List<Selection> selections = new ArrayList<>();
        int steps = 0;
        while (!pending.isEmpty()) {
            flow.checkpoint();
            if (++steps > maxSteps || selections.size() >= maxPaths || pending.size() > maxSteps) {
                issues.add("Joint condition dependency/path limit exceeded"); break;
            }
            Search state = pending.removeLast();
            Requirement missing = state.requirements().values().stream().filter(requirement -> {
                Path path = state.paths().get(requirement.frame());
                return path == null || !path.order().containsAll(requirement.statements())
                        || !requirement.returns().isEmpty() && Collections.disjoint(path.order(), requirement.returns());
            }).findFirst().orElse(null);
            if (missing != null) {
                Requirement refined = lock(missing, state.paths().get(missing.frame()));
                Paths candidates = cache.computeIfAbsent(refined, this::paths);
                issues.addAll(candidates.issues());
                for (Path path : candidates.values()) {
                    var selected = new LinkedHashMap<>(state.paths()); selected.put(path.frame(), path);
                    if (!solver.contradicts(conditions(selected, state.dispatches(), false)))
                        pending.add(new Search(state.requirements(), selected, state.dispatches()));
                }
                continue;
            }
            List<Condition> conditions = conditions(state.paths(), state.dispatches(), true);
            Node returned = null;
            for (Condition condition : conditions) {
                returned = unselectedReturn(condition.expression().root(), state.paths(), 0);
                if (returned != null) break;
            }
            if (returned == null) {
                if (!solver.contradicts(conditions)) selections.add(new Selection(Map.copyOf(state.paths()), conditions, state.dispatches()));
                continue;
            }
            Map<Frame, Set<Integer>> targets = new LinkedHashMap<>();
            boolean external = false;
            for (Node input : returned.inputs()) {
                Frame frame = Frame.of(input.variable());
                if (returnSource(input, Frame.of(returned.variable())))
                    targets.computeIfAbsent(frame, ignored -> new LinkedHashSet<>()).add(input.variable().statementIndex());
                else external = true;
            }
            for (var target : targets.entrySet()) {
                var choices = new LinkedHashMap<>(state.dispatches());
                if (!chooseFrame(choices, target.getKey())) continue;
                var next = new LinkedHashMap<>(state.requirements());
                Requirement prior = next.get(target.getKey());
                next.put(target.getKey(), new Requirement(target.getKey(), prior == null ? Set.of() : prior.statements(),
                        prior == null ? Map.of() : prior.definitions(), prior == null ? Map.of() : prior.successors(), Set.copyOf(target.getValue())));
                // 调用链上的每个调用位置都必须实际经过，不能只验证最内层返回值。
                for (int i = 0; i < target.getKey().context().size(); i++) {
                    InvocationSite call = call(target.getKey().context().get(i));
                    Frame owner = new Frame(call.method().getSignature(), target.getKey().context().subList(0, i));
                    Requirement old = next.getOrDefault(owner, new Requirement(owner, Set.of(), Map.of(), Map.of(), Set.of()));
                    var statements = new LinkedHashSet<>(old.statements()); statements.add(call.statementIndex());
                    next.put(owner, new Requirement(owner, Set.copyOf(statements), old.definitions(), old.successors(), old.returns()));
                }
                pending.add(new Search(next, state.paths(), Map.copyOf(choices)));
            }
            if (external || targets.isEmpty()) {
                var choices = new LinkedHashMap<>(state.dispatches());
                if (choose(choices, dispatchKey(returned), Optional.empty()))
                    pending.add(new Search(state.requirements(), state.paths(), Map.copyOf(choices)));
            }
        }
        return new Result(List.copyOf(selections), List.copyOf(issues));
    }

    private Requirement lock(Requirement requirement, Path path) {
        if (path == null) return requirement;
        var selected = new HashMap<>(requirement.successors());
        var method = method(requirement.frame());
        for (Edge edge : path.branches()) {
            Stmt stmt = method.getBody().getStmts().get(edge.statement());
            Stmt target = method.getBody().getControlFlowGraph().successors(stmt).get(edge.successor());
            selected.put(edge.statement(), flow.program().statementIndex(method, target));
        }
        return new Requirement(requirement.frame(), requirement.statements(), requirement.definitions(), Map.copyOf(selected), requirement.returns());
    }

    private boolean returnSource(Node node, Frame caller) {
        return !Frame.of(node.variable()).equals(caller) && node.variable().statementIndex() >= 0
                && method(Frame.of(node.variable())).getBody().getStmts().get(node.variable().statementIndex()) instanceof JReturnStmt;
    }

    private Node unselectedReturn(Node node, Map<Frame, Path> paths, int depth) {
        if (depth > maxDependencyDepth) return null;
        if (node.kind() == Kind.RETURN && node.computation().operation() == Operation.ALTERNATIVES) {
            boolean matched = node.inputs().stream().anyMatch(input -> returnSource(input, Frame.of(node.variable()))
                    && paths.containsKey(Frame.of(input.variable())) && paths.get(Frame.of(input.variable())).order().contains(input.variable().statementIndex()));
            if (!matched) return node;
        } else if (node.computation().operation() == Operation.ALTERNATIVES && node.inputs().size() > 1) return null;
        for (Node input : node.inputs()) {
            Node found = unselectedReturn(input, paths, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private void add(Variable variable, Map<Frame, Set<Integer>> required) {
        if (variable.statementIndex() >= 0)
            required.computeIfAbsent(Frame.of(variable), ignored -> new LinkedHashSet<>()).add(variable.statementIndex());
    }

    /** 从完整调用栈登记每一层目标，避免两个不同实现经同一个内部 helper 返回时漏掉上层冲突。 */
    private boolean chooseFrame(Map<DispatchKey, Optional<MethodSignature>> choices, Frame frame) {
        for (int i = 0; i < frame.context().size(); i++) {
            SqlCallSite site = frame.context().get(i);
            Frame owner = new Frame(site.caller().orElseThrow(), frame.context().subList(0, i));
            MethodSignature target = i + 1 < frame.context().size()
                    ? frame.context().get(i + 1).caller().orElseThrow() : frame.method();
            if (!choose(choices, new DispatchKey(owner, site), Optional.of(target))) return false;
        }
        return true;
    }

    private static boolean choose(Map<DispatchKey, Optional<MethodSignature>> choices, DispatchKey key,
                                  Optional<MethodSignature> target) {
        var old = choices.putIfAbsent(key, target);
        return old == null || old.equals(target);
    }

    private DispatchKey dispatchKey(Node node) {
        var variable = node.variable();
        Frame owner = Frame.of(variable);
        JavaSootMethod method = method(owner);
        Stmt stmt = method.getBody().getStmts().get(variable.statementIndex());
        var site = node.computation().method().flatMap(target -> flow.program().invocation(method.getSignature(), variable.statementIndex(), target))
                .orElseGet(() -> new InvocationSite(method, stmt, variable.statementIndex(),
                        stmt.asInvokableStmt().getInvokeExpr().orElseThrow(), flow.program().view()));
        return new DispatchKey(owner, SqlCallSite.invocation(site));
    }

    private void collect(Node node, Map<Frame, Set<Integer>> required, Map<Frame, Map<Use, Integer>> definitions,
                         Set<Node> seen, boolean[] conflict, Set<String> issues,
                         Map<DispatchKey, Optional<MethodSignature>> dispatches, Map<Frame, Map<Integer, Integer>> successors) {
        flow.checkpoint();
        if (!seen.add(node)) return;
        if (seen.size() > maxSteps) { issues.add("Joint trace inspection limit exceeded"); return; }
        add(node.variable(), required);
        // 符号槽位可以携带尚未选定的分支，不能要求这些互斥来源同时执行。
        if (node.computation().operation() == Operation.ALTERNATIVES && node.inputs().size() > 1) return;
        for (ControlEdge edge : node.computation().history()) {
            add(edge.from(), required); add(edge.to(), required);
            if (edge.exceptional()) issues.add("Joint value snapshot includes exceptional control flow");
            if (!Frame.of(edge.from()).equals(Frame.of(edge.to()))) continue;
            var choices = successors.computeIfAbsent(Frame.of(edge.from()), ignored -> new HashMap<>());
            var old = choices.putIfAbsent(edge.from().statementIndex(), edge.to().statementIndex());
            if (old != null && old != edge.to().statementIndex()) conflict[0] = true;
        }
        // 外部实现也是独立的分派分支，不能与同次调用已经选定的项目实现拼成一组参数。
        if (node.kind() == Kind.RETURN && node.inputs().size() == 1 && node.inputs().getFirst().kind() == Kind.EXTERNAL_CALL
                && !returnSource(node.inputs().getFirst(), Frame.of(node.variable()))
                && !choose(dispatches, dispatchKey(node), Optional.empty())) conflict[0] = true;
        if (node.kind() == Kind.DEFINITION && node.inputs().size() == 1) {
            Node selected = node.inputs().getFirst();
            Variable source = selected.variable();
            if (Frame.of(source).equals(Frame.of(node.variable())) && selected.kind() != Kind.UNRESOLVED
                    && selected.kind() != Kind.CYCLE && selected.kind() != Kind.LIMIT && source.statementIndex() >= 0
                    && method(Frame.of(source)).getBody().getStmts().get(source.statementIndex()) instanceof AbstractDefinitionStmt definition
                    && definition.getLeftOp().toString().equals(node.variable().expression())) {
                var choices = definitions.computeIfAbsent(Frame.of(source), ignored -> new HashMap<>());
                Integer old = choices.putIfAbsent(new Use(node.variable().statementIndex(), node.variable().expression()), source.statementIndex());
                if (old != null && old != source.statementIndex()) conflict[0] = true;
            }
        }
        if (node.kind() == Kind.CYCLE || node.kind() == Kind.LIMIT || node.kind() == Kind.FIELD)
            issues.add("Joint path contains unresolved cycle, limit or heap dependency");
        for (Node input : node.inputs()) collect(input, required, definitions, seen, conflict, issues, dispatches, successors);
    }

    private Paths paths(Requirement requirement) {
        JavaSootMethod method = method(requirement.frame());
        var statements = method.getBody().getStmts();
        var graph = method.getBody().getControlFlowGraph();
        Map<Stmt, Integer> positions = new HashMap<>();
        for (int i = 0; i < statements.size(); i++) positions.put(statements.get(i), i);
        var slice = new JointControlFlowSlice(method, requirement.statements(), requirement.returns(), flow::checkpoint);
        record State(Stmt statement, List<Integer> order, Map<String, Integer> latest,
                     Map<Use, Integer> definitions, List<Edge> branches) {}
        record StateKey(Stmt statement, Set<Integer> visited, Map<String, Integer> latest,
                        Map<Use, Integer> definitions, List<Edge> branches) {}
        Set<StateKey> visited = new HashSet<>();
        Deque<State> pending = new ArrayDeque<>();
        pending.add(new State(graph.getStartingStmt(), List.of(), Map.of(), Map.of(), List.of()));
        List<Path> found = new ArrayList<>();
        Set<String> issues = new LinkedHashSet<>();
        Map<Stmt, Set<Integer>> reachable = new HashMap<>();
        int steps = 0;
        while (!pending.isEmpty()) {
            flow.checkpoint();
            if (++steps > maxSteps || found.size() >= maxPaths) { issues.add("Joint control-flow exploration limit exceeded"); break; }
            State state = pending.removeLast();
            int index = positions.get(state.statement());
            if (state.order().contains(index)) { issues.add("Joint loop path requires unrolling"); continue; }
            if (state.order().size() >= maxControlFlowDepth) { issues.add("Joint control-flow depth limit exceeded"); continue; }
            if (!slice.cyclic()) {
                Set<Integer> relevantVisited = new HashSet<>(state.order());
                var relevantStatements = new HashSet<>(requirement.statements()); relevantStatements.addAll(requirement.returns());
                relevantVisited.retainAll(relevantStatements);
                if (!visited.add(new StateKey(state.statement(), relevantVisited, state.latest(), state.definitions(), state.branches()))) continue;
            }
            boolean compatible = requirement.definitions().entrySet().stream().filter(e -> e.getKey().statement() == index)
                    .allMatch(e -> Objects.equals(state.latest().get(e.getKey().local()), e.getValue()));
            if (!compatible) continue;
            var order = new ArrayList<>(state.order()); order.add(index);
            var used = new HashMap<>(state.definitions());
            // 只保存真正读取的局部量，避免为每条语句复制整张“所有历史局部量”表。
            for (var value : state.statement().getUses()) if (value instanceof Local local && slice.relevant(local)) {
                Integer definition = state.latest().get(local.toString());
                if (definition != null) used.put(new Use(index, local.toString()), definition);
            }
            if (order.containsAll(requirement.statements()) && (requirement.returns().isEmpty() || requirement.returns().contains(index))) {
                found.add(new Path(requirement.frame(), List.copyOf(order), Map.copyOf(used), state.branches())); continue;
            }
            if (!graph.exceptionalSuccessors(state.statement()).isEmpty()) {
                var remaining = new HashSet<>(requirement.statements()); remaining.removeAll(order);
                for (Stmt handler : graph.exceptionalSuccessors(state.statement()).values()) {
                    var destinations = reachable.computeIfAbsent(handler, start -> {
                        Set<Integer> reached = new HashSet<>();
                        Deque<Stmt> work = new ArrayDeque<>(); work.add(start);
                        while (!work.isEmpty()) {
                            flow.checkpoint();
                            Stmt stmt = work.removeFirst();
                            if (reached.add(positions.get(stmt))) work.addAll(graph.getAllSuccessors(stmt));
                        }
                        return reached;
                    });
                    // 直接退出/重抛且不可能再经过目标 SQL 的异常边，不影响该候选的可达性。
                    if (destinations.containsAll(remaining)) issues.add("Joint exceptional control flow is not modeled");
                }
            }
            var latest = new HashMap<>(state.latest());
            if (state.statement() instanceof AbstractDefinitionStmt definition && definition.getLeftOp() instanceof Local local && slice.relevant(local))
                latest.put(local.toString(), index);
            var successors = graph.successors(state.statement());
            if (successors.size() > 1 && !requirement.successors().containsKey(index)) {
                var join = slice.join(state.statement());
                if (join.isPresent()) {
                    pending.add(new State(join.get(), order, latest, used, state.branches())); continue;
                }
            }
            for (int i = successors.size() - 1; i >= 0; i--) {
                Integer requiredSuccessor = requirement.successors().get(index);
                if (requiredSuccessor != null && !requiredSuccessor.equals(positions.get(successors.get(i)))) continue;
                var branches = new ArrayList<>(state.branches());
                if (state.statement() instanceof JIfStmt || state.statement() instanceof JSwitchStmt) branches.add(new Edge(index, i));
                else if (successors.size() > 1) issues.add("Unmodeled joint control-flow branch");
                if (branches.size() != state.branches().size()) {
                    var prefix = new Path(requirement.frame(), order, used, branches);
                    if (solver.contradicts(conditions(Map.of(requirement.frame(), prefix), Map.of(), false))) continue;
                }
                pending.add(new State(successors.get(i), order, latest, used, List.copyOf(branches)));
            }
        }
        return new Paths(List.copyOf(found), List.copyOf(issues));
    }

    private List<Condition> conditions(Map<Frame, Path> selected, Map<DispatchKey, Optional<MethodSignature>> dispatches, boolean keepReturns) {
        List<Condition> conditions = new ArrayList<>();
        for (Path path : selected.values()) {
            var method = method(path.frame());
            var context = new CallContext(path.frame().context().stream().map(this::call).toList());
            for (Edge edge : path.branches()) {
                Stmt stmt = method.getBody().getStmts().get(edge.statement());
                FlowPoint point = new FlowPoint(method, stmt, context);
                if (stmt instanceof JIfStmt branch) {
                    ValueTrace trace = conditionCache.computeIfAbsent(new ConditionKey(path.frame(), edge.statement()),
                            ignored -> flow.trace(point.value(branch.getCondition())));
                    conditions.add(new Condition(select(trace, selected, dispatches, keepReturns), edge.successor() == JIfStmt.TRUE_BRANCH_IDX));
                } else if (stmt instanceof JSwitchStmt branch) {
                    ValueTrace trace = conditionCache.computeIfAbsent(new ConditionKey(path.frame(), edge.statement()),
                            ignored -> flow.trace(point.value(branch.getKey())));
                    ValueTrace key = select(trace, selected, dispatches, keepReturns);
                    // default 是对全部 case 的不等式合取；合并 target 仍按各条边分别保留。
                    for (int i = 0; i < branch.getValues().size(); i++) if (edge.successor() == i || edge.successor() == branch.getValues().size()) {
                        Node literal = new Node(key.variable().withType("int"), Kind.CONSTANT, "switch case", List.of(),
                                new Computation(Operation.LITERAL, Integer.toString(branch.getValue(i))));
                        Node predicate = new Node(key.variable().withType("boolean"), Kind.EXPRESSION, "switch equality",
                                List.of(key.root(), literal), new Computation(Operation.BINARY, "=="));
                        conditions.add(new Condition(new ValueTrace(predicate.variable(), key.callChain(), predicate), edge.successor() == i));
                    }
                }
            }
        }
        return List.copyOf(conditions);
    }

    ValueTrace select(ValueTrace trace, Selection selection) {
        return select(trace, selection.paths(), selection.dispatches(), false);
    }

    private ValueTrace select(ValueTrace trace, Map<Frame, Path> paths, Map<DispatchKey, Optional<MethodSignature>> dispatches, boolean keepReturns) {
        return new ValueTrace(trace.variable(), trace.callChain(), select(trace.root(), paths, dispatches, keepReturns, 0));
    }

    private Node select(Node node, Map<Frame, Path> paths, Map<DispatchKey, Optional<MethodSignature>> dispatches, boolean keepReturns, int depth) {
        flow.checkpoint();
        if (depth > maxDependencyDepth) return new Node(node.variable(), Kind.LIMIT, "Joint condition depth limit exceeded", List.of());
        List<Node> inputs = node.inputs();
        Path path = paths.get(Frame.of(node.variable()));
        if (node.kind() == Kind.DEFINITION && path != null) {
            Integer definition = path.definitions().get(new Use(node.variable().statementIndex(), node.variable().expression()));
            if (definition != null) inputs = inputs.stream().filter(input -> input.variable().statementIndex() == definition).toList();
        } else if (node.kind() == Kind.RETURN && node.computation().operation() == Operation.ALTERNATIVES) {
            if (Optional.empty().equals(dispatches.get(dispatchKey(node))))
                return new Node(node.variable(), Kind.UNRESOLVED, "Condition includes external or unresolved return", List.of());
            var matched = inputs.stream().filter(input -> {
                Path callee = paths.get(Frame.of(input.variable()));
                return returnSource(input, Frame.of(node.variable())) && callee != null && callee.order().contains(input.variable().statementIndex());
            }).toList();
            if (!matched.isEmpty()) inputs = matched;
            else if (!keepReturns) return new Node(node.variable(), Kind.UNRESOLVED, "Condition callee control flow was not selected", List.of());
        }
        return new Node(node.variable(), node.kind(), node.detail(), inputs.stream()
                .map(input -> select(input, paths, dispatches, keepReturns, depth + 1)).toList(), node.computation());
    }

    private JavaSootMethod method(Frame frame) { return flow.program().view().getMethod(frame.method()).orElseThrow(); }
    private InvocationSite call(SqlCallSite snapshot) {
        return calls.computeIfAbsent(snapshot, key -> {
            var indexed = flow.program().invocation(key); if (indexed.isPresent()) return indexed.get();
            JavaSootMethod method = flow.program().view().getMethod(key.caller().orElseThrow()).orElseThrow();
            var statements = method.getBody().getStmts();
            for (int i = 0; i < statements.size(); i++) {
                Stmt stmt = statements.get(i);
                if (stmt.isInvokableStmt() && stmt.asInvokableStmt().getInvokeExpr().isPresent()) {
                    var site = new InvocationSite(method, stmt, i, stmt.asInvokableStmt().getInvokeExpr().orElseThrow(), flow.program().view());
                    if (SqlCallSite.invocation(site).equals(key)) return site;
                }
            }
            throw new IllegalArgumentException("Cannot resolve saved call context: " + key);
        });
    }
}
