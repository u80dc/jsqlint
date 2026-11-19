package org.u80dc.jsqlint.core.node;

import java.util.Objects;

/**
 * 可机器识别的诊断代码、上下文说明和显式结果影响；代码仅用于展示和检索。
 *
 * @author u80dc
 *
 */
public record SqlDiagnostic(String code, String message, Effect effect) {
    public enum Effect { UNRESOLVED, LIMIT, UNAVAILABLE }

    /** 旧入口保留普通未知值语义；容量和缺失快照由生产方显式声明。 */
    public SqlDiagnostic(String code, String message) {
        this(code, message, Effect.UNRESOLVED);
    }

    public SqlDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(effect, "effect");
    }

    public boolean limited() { return effect == Effect.LIMIT; }
    public boolean unavailable() { return effect == Effect.UNAVAILABLE; }
}
