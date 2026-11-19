package org.u80dc.jsqlint.core.node;

import org.u80dc.jsqlint.core.ReplayBudgets;
import org.u80dc.jsqlint.core.SqlRenderBudgets;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 单次渲染的表达式执行策略及活动值；可在同次求值中共享，不能保存为持久结果。
 * 冻结的求值器只保存预算，检查点及活动引用属于每次执行。
 */
public final class SqlEvaluationContext {
    private static final ThreadLocal<SqlEvaluationContext> CURRENT = new ThreadLocal<>();
    private final ReplayBudgets replayBudgets;
    private final SqlRenderBudgets renderBudgets;
    private final Checkpoints checkpoints;
    private final Consumer<String> limits;
    private final SqlEvaluationTracker.Activity activity;
    private final boolean explicitReplay;

    public SqlEvaluationContext(ReplayBudgets replayBudgets, Runnable checkpoint, Consumer<String> limits) {
        this(replayBudgets, SqlRenderBudgets.fromReplay(replayBudgets), checkpoint, limits);
    }
    public SqlEvaluationContext(ReplayBudgets replayBudgets, SqlRenderBudgets renderBudgets, Runnable checkpoint, Consumer<String> limits) {
        this(replayBudgets, renderBudgets, new Checkpoints(checkpoint, 1), limits, true, new SqlEvaluationTracker.Activity());
    }
    public SqlEvaluationContext(ReplayBudgets replayBudgets, SqlRenderBudgets renderBudgets, Runnable checkpoint) {
        this(replayBudgets, renderBudgets, checkpoint, ignored -> {});
    }
    private SqlEvaluationContext(ReplayBudgets replayBudgets, SqlRenderBudgets renderBudgets, Checkpoints checkpoints, Consumer<String> limits,
                                  boolean explicitReplay, SqlEvaluationTracker.Activity activity) {
        this.replayBudgets = Objects.requireNonNull(replayBudgets);
        this.renderBudgets = Objects.requireNonNull(renderBudgets);
        this.checkpoints = Objects.requireNonNull(checkpoints);
        this.limits = Objects.requireNonNull(limits);
        this.explicitReplay = explicitReplay;
        this.activity = activity;
    }
    public SqlEvaluationContext(ReplayBudgets replayBudgets, Runnable checkpoint) {
        this(replayBudgets, checkpoint, ignored -> {});
    }
    public ReplayBudgets replayBudgets() { return replayBudgets; }
    public SqlRenderBudgets renderBudgets() { return renderBudgets; }
    SqlEvaluationTracker.Activity activity() { return activity; }

    /** 新引用扩展可显式返回活动身份；句柄不能跨执行复用，也不暴露可修改的原始值。 */
    public SqlExpressionEvaluator.ActivityValue activityValue(Object value, SqlExpressionEvaluator.Reference reference) {
        return new SqlExpressionEvaluator.ActivityValue(activity.value(value), reference, activity);
    }

    /**
     * 为旧 SqlValue 返回签名关联本次结果的活动值、来源及容量状态。
     * 只有正在进行的引用求值收集这些信息，忽略的结果和嵌套求值不会覆盖返回结果。
     */
    public SqlValue result(SqlValue snapshot, Object value, SqlExpressionEvaluator.Reference reference, Optional<String> limit) {
        Objects.requireNonNull(snapshot); Objects.requireNonNull(limit);
        return activity.results.record(snapshot, snapshot instanceof SqlValue.Known ? activityValue(value, reference) : null, limit);
    }

    /** 同一次执行的报告视图，保持预算、活动身份及取消策略。 */
    public SqlEvaluationContext reportingTo(Consumer<String> listener) {
        Objects.requireNonNull(listener);
        return new SqlEvaluationContext(replayBudgets, renderBudgets, checkpoints, message -> {
            limits.accept(message);
            listener.accept(message);
        }, explicitReplay, activity);
    }

    /** 无活动作用域时每次创建独立默认状态，不跨渲染共享可变引用。 */
    public static SqlEvaluationContext defaults(Runnable checkpoint, Consumer<String> limits) {
        return new SqlEvaluationContext(ReplayBudgets.defaults(), SqlRenderBudgets.defaults(), new Checkpoints(checkpoint, 64), limits, false,
                new SqlEvaluationTracker.Activity());
    }
    public static SqlEvaluationContext current() {
        SqlEvaluationContext current = CURRENT.get();
        return current != null ? current : defaults(() -> {}, ignored -> {});
    }
    public static SqlEvaluationContext currentOr(ReplayBudgets fallback) {
        SqlEvaluationContext current = CURRENT.get();
        if (current == null) return new SqlEvaluationContext(fallback, () -> {});
        return current.explicitReplay ? current
                : new SqlEvaluationContext(fallback, current.renderBudgets, current.checkpoints, current.limits, true, current.activity);
    }

    public void check() {
        check(false);
    }
    private void check(boolean immediate) {
        try { checkpoints.check(immediate); }
        catch (RuntimeException error) {
            if (error instanceof CheckpointFailure) throw error;
            throw new CheckpointFailure(error);
        }
    }
    public void limit(String message) { limits.accept(message); }

    /** 兼容旧求值器扩展；作用域退出（包括取消）时恢复此前上下文。 */
    public <T> T evaluate(Supplier<T> evaluation) {
        Objects.requireNonNull(evaluation);
        SqlEvaluationContext previous = CURRENT.get();
        CURRENT.set(this);
        try {
            check(previous == null);
            T result = evaluation.get();
            check(previous == null);
            return result;
        } catch (CheckpointFailure failure) {
            if (previous != null) throw failure;
            throw failure.original;
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    /** 表达式不支持与主动停止不是同一类结果，局部错误处理不能吞掉检查点信号。 */
    public static void rethrowControl(RuntimeException error) {
        if (error instanceof CheckpointFailure) throw error;
    }
    private static final class CheckpointFailure extends RuntimeException {
        private final RuntimeException original;
        private CheckpointFailure(RuntimeException original) {
            super(original.getMessage(), original, false, false);
            this.original = original;
        }
    }
    /** 旧入口按小批次检查新增内部工作，显式上下文仍逐步检查；状态由一次执行的视图共享。 */
    private static final class Checkpoints {
        private final Runnable checkpoint;
        private final int interval;
        private int steps;
        private Checkpoints(Runnable checkpoint, int interval) {
            this.checkpoint = Objects.requireNonNull(checkpoint); this.interval = interval;
        }
        private void check(boolean immediate) {
            if (immediate || ++steps >= interval) { steps = 0; checkpoint.run(); }
        }
    }
}
