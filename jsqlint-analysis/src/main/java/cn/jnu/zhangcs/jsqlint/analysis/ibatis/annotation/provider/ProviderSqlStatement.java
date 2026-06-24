package cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider;

import org.jspecify.annotations.NonNull;
import sootup.core.signatures.MethodSignature;

import java.util.List;
import java.util.Objects;

/**
 * 分析结果
 *
 * @param method 注解定义的方法
 * @param sql SQL文本
 * @param diagnostics SQL构造过程中可能存在的错误信息
 * @author zhangcs
 */
public record ProviderSqlStatement(MethodSignature method, String sql, List<String> diagnostics) {

    public ProviderSqlStatement(@NonNull MethodSignature method, String sql, List<String> diagnostics) {
        this.method = Objects.requireNonNull(method, "method");
        this.sql = sql;
        this.diagnostics = List.copyOf(diagnostics);
    }

    public boolean hasSql() {
        return sql != null && !sql.isBlank();
    }

    @Override
    public @NonNull String toString() {
        if (hasSql()) {
            return sql;
        }
        return "";
    }

}
