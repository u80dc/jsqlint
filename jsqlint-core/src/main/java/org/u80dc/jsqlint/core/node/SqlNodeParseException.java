package org.u80dc.jsqlint.core.node;

/**
 * 严格解析入口的异常，保留完整失败输入和诊断。
 *
 * @author u80dc
 *
 */
public final class SqlNodeParseException extends IllegalArgumentException {
    private final SqlNodeBuilder.ParseResult result;

    public SqlNodeParseException(SqlNodeBuilder.ParseResult result) {
        super(result.diagnostics().toString());
        this.result = result;
    }

    public SqlNodeBuilder.ParseResult result() {
        return result;
    }
}
