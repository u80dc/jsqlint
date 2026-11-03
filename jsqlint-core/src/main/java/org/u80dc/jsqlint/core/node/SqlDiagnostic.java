package org.u80dc.jsqlint.core.node;

import java.util.Objects;

/**
 * 可机器识别的诊断代码和上下文说明。
 *
 * @author u80dc
 *
 */
public record SqlDiagnostic(String code, String message) {
    public SqlDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
