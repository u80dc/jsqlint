package cn.jnu.zhangcs.jsqlint.analysis.jdbc;

import cn.jnu.zhangcs.jsqlint.analysis.SqlSourceType;
import cn.jnu.zhangcs.jsqlint.analysis.extractor.*;
import sootup.core.signatures.MethodSignature;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 提取传递给原生 java.sql JDBC API 的 SQL.
 *
 * @author zhangcs
 */
public final class NativeJdbcSqlExtractor implements SqlExtractor {

    @Override
    public String name() {
        return "jdbc";
    }

    @Override
    public SqlExtractionResult extract(SqlExtractorContext context) {
        return SqlExtractionSupport.extractCallSites(context, this::matchTarget);
    }

    private Optional<SqlCallTarget> matchTarget(MethodSignature signature) {
        if (!hasStringSqlArgument(signature)) {
            return Optional.empty();
        }

        String className = signature.getDeclClassType().getFullyQualifiedName();
        if (!className.startsWith("java.sql.")) {
            return Optional.empty();
        }

        JdbcTargetMethod method = JdbcTargetMethod.fromMethod(className, signature.getName());
        if (method == null || !method.isSqlParam(0)) {
            return Optional.empty();
        }

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("jdbcCategory", method.getCategory());
        extra.put("jdbcSignature", method.getJimpleSignature());

        return Optional.of(new SqlCallTarget(
                name(),
                method.getClassName() + "#" + method.getMethodName(),
                0,
                SqlSourceType.PARAM_SQL,
                List.of(name(), method.getCategory()),
                extra));
    }

    private boolean hasStringSqlArgument(MethodSignature signature) {
        return signature != null
                && signature.getParameterCount() > 0
                && "java.lang.String".equals(signature.getParameterTypes().getFirst().toString());
    }
}
