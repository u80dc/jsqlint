package cn.jnu.zhangcs.jsqlint.analysis.spring;

import cn.jnu.zhangcs.jsqlint.analysis.SqlSourceType;
import cn.jnu.zhangcs.jsqlint.analysis.extractor.*;
import sootup.core.signatures.MethodSignature;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 提取传递给 Spring JDBC 模板 API 的 SQL 语句.
 *
 * @author zhangcs
 */
public final class SpringJdbcSqlExtractor implements SqlExtractor {

    @Override
    public String name() {
        return "spring-jdbc";
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
        if (!className.startsWith("org.springframework.jdbc.")) {
            return Optional.empty();
        }

        JdbcTemplateTargetMethod method = JdbcTemplateTargetMethod.fromMethod(className, signature.getName());
        if (method == null || !method.isSqlParam(0)) {
            return Optional.empty();
        }

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("springJdbcCategory", method.getCategory());
        extra.put("springJdbcSignature", method.getJimpleSignature());

        return Optional.of(new SqlCallTarget(
                name(),
                method.getClassName() + "#" + method.getMethodName(),
                0,
                SqlSourceType.PARAM_SQL,
                tags(method),
                extra));
    }

    private List<String> tags(JdbcTemplateTargetMethod method) {
        if (method.isNamedParameterMethod()) {
            return List.of(name(), "named-parameter");
        }
        return List.of(name(), "jdbc-template");
    }

    private boolean hasStringSqlArgument(MethodSignature signature) {
        return signature != null
                && signature.getParameterCount() > 0
                && "java.lang.String".equals(signature.getParameterTypes().getFirst().toString());
    }
}
