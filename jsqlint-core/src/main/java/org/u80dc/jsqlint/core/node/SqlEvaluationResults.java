package org.u80dc.jsqlint.core.node;

import java.util.*;
import java.util.function.Supplier;

import static org.u80dc.jsqlint.core.node.SqlExpressionEvaluator.ActivityValue;

/** The legacy SqlValue return signature is bridged by result identity inside one evaluation frame. */
final class SqlEvaluationResults {
    record Result(SqlValue value, Optional<ActivityValue> activity, Optional<String> limit) {}

    private static final class Frame {
        final Map<SqlValue, Result> results = new IdentityHashMap<>();
        final SqlEvaluationContext context;
        String limit;
        Frame(SqlEvaluationContext context) { this.context = context; }
    }

    private final Deque<Frame> frames = new ArrayDeque<>();

    SqlValue record(SqlValue value, ActivityValue activity, Optional<String> limit) {
        Objects.requireNonNull(value); Objects.requireNonNull(limit);
        if (!frames.isEmpty()) {
            var frame = frames.getFirst();
            if (frame.limit != null) return new SqlValue.Unknown(frame.limit);
            if (!frame.results.containsKey(value) && frame.results.size() >= frame.context.replayBudgets().steps()) {
                frame.limit = "Expression result bridge work budget exceeded";
                frame.context.limit(frame.limit);
                return new SqlValue.Unknown(frame.limit);
            }
            frame.results.put(value, new Result(value, Optional.ofNullable(activity), limit));
        }
        return value;
    }

    Result capture(Supplier<SqlValue> evaluation, SqlEvaluationContext context) {
        var frame = new Frame(context);
        frames.addFirst(frame);
        try {
            SqlValue value = Objects.requireNonNull(evaluation.get(), "Evaluator returned null");
            if (frame.limit != null) return new Result(new SqlValue.Unknown(frame.limit), Optional.empty(), Optional.of(frame.limit));
            return frame.results.getOrDefault(value, new Result(value, Optional.empty(), Optional.empty()));
        } finally {
            frames.removeFirst();
        }
    }
}
