package cn.jnu.zhangcs.jsqlint.audit;

/**
 * SQL审核过程中的运行时异常。
 *
 * <p>主要用于封装 Drools 规则编译失败、规则执行失败等底层异常，
 * 让调用方可以按审核失败统一处理。</p>
 */
public class SqlAuditException extends RuntimeException {

    /**
     * 创建仅包含错误信息的审核异常。
     */
    public SqlAuditException(String message) {
        super(message);
    }

    /**
     * 创建包含错误信息和原始异常的审核异常。
     */
    public SqlAuditException(String message, Throwable cause) {
        super(message, cause);
    }
}
