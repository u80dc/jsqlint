package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.SqlDiscovery.ExtractionRequest;
import org.u80dc.jsqlint.core.SqlDiscovery.Pending;
import org.u80dc.jsqlint.core.template.SqlTemplateProjection;
import org.u80dc.jsqlint.core.node.SqlNode;
import org.u80dc.jsqlint.core.spi.SqlInput;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.signatures.MethodSignature;

import java.util.*;
import java.util.function.Consumer;

/** 旧 ValueAt 起点与完整提取结果的复用和事件投影；执行诊断与交付仍由会话管理。 @author u80dc */
final class SqlSeedCompatibility {
    private final Set<Point> extractionPoints = new HashSet<>();
    private final Map<Point, List<Legacy>> pending = new LinkedHashMap<>();
    private final Map<Point, List<SqlExtraction.Statement>> statements = new LinkedHashMap<>();

    SqlSeedCompatibility(List<ExtractionRequest> requests) {
        requests.forEach(request -> extractionPoints.add(point(request)));
    }

    boolean defer(Pending item, SqlInput.ValueAt input) {
        var point = new Point(item.moduleId(), input.method().getSignature(), input.statement());
        if (!extractionPoints.contains(point)) return false;
        pending.computeIfAbsent(point, ignored -> new ArrayList<>()).add(new Legacy(item, input));
        return true;
    }

    void capture(ExtractionRequest request, List<SqlExtraction> results) {
        var point = point(request);
        if (pending.containsKey(point)) statements.computeIfAbsent(point, ignored -> new ArrayList<>())
                .addAll(results.stream().flatMap(result -> result.statements().stream()).toList());
    }

    /** 每个原始 seed 交付一次；其他规则的 SQL 输入不会混入，没有匹配结果时由会话恢复原始输入。 */
    void replay(Consumer<Replay> deliver) {
        for (var entry : pending.entrySet()) for (var legacy : entry.getValue()) {
            deliver.accept(new Replay(legacy.item(), legacy.input(), statements.getOrDefault(entry.getKey(), List.of())));
        }
    }

    private static boolean sourceMatches(SqlExtraction.Statement statement, SqlInput.ValueAt input) {
        if (statement.sql().isEmpty()) return false;
        var source = statement.sql().get().variable();
        return source.method().equals(input.method().getSignature())
                && source.statementIndex() == input.method().getBody().getStmts().indexOf(input.statement())
                && source.expression().equals(input.value().toString()) && source.type().equals(input.value().getType().toString());
    }

    private static Point point(ExtractionRequest request) {
        return new Point(request.moduleId(), request.site().method().getSignature(), request.site().statement());
    }

    /** 投影在会话的局部恢复边界内执行，交付枚举本身不启动图遍历。 */
    record Replay(Pending item, SqlInput.ValueAt input, List<SqlExtraction.Statement> statements) {
        Optional<SqlNode> template(SqlRecoveryBudgets budgets, Runnable checkpoint) {
            List<SqlNode> templates = new ArrayList<>();
            for (var statement : statements) {
                checkpoint.run();
                if (sourceMatches(statement, input)) templates.add(statement.template());
            }
            return templates.isEmpty() ? Optional.empty()
                    : Optional.of(SqlTemplateProjection.alternatives(templates, budgets, checkpoint));
        }
    }
    private record Legacy(Pending item, SqlInput.ValueAt input) {}
    private record Point(String moduleId, MethodSignature method, Stmt statement) {}
}
