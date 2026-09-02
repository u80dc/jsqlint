package cn.jnu.zhangcs.jsqlint.analysis.ibatis;

import cn.jnu.zhangcs.jsqlint.analysis.AnalyzeResult;
import cn.jnu.zhangcs.jsqlint.analysis.SourceLocation;
import cn.jnu.zhangcs.jsqlint.analysis.SqlSourceType;
import cn.jnu.zhangcs.jsqlint.analysis.StatementType;
import cn.jnu.zhangcs.jsqlint.analysis.extractor.*;
import cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.ProviderSqlStatement;
import cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.resolver.MapperResolver;
import cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.resolver.MappingResolver;
import cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.resolver.ProviderSqlResolver;
import cn.jnu.zhangcs.jsqlint.analysis.ibatis.impl.MapperImplBuilder;
import cn.jnu.zhangcs.jsqlint.analysis.ibatis.xml.XmlMapper;
import cn.jnu.zhangcs.jsqlint.analysis.ibatis.xml.XmlMapperParser;
import cn.jnu.zhangcs.jsqlint.analysis.node.SqlNode;
import cn.jnu.zhangcs.jsqlint.analysis.node.SqlNodeBuilder;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.MethodParameterRef;
import cn.jnu.zhangcs.jsqlint.analysis.util.FileUtils;
import lombok.extern.slf4j.Slf4j;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.Type;
import sootup.java.core.AnnotationUsage;
import sootup.java.core.JavaSootClass;
import sootup.java.core.JavaSootMethod;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.AnnotationUtils.*;

/**
 * SQL提取，MyBatis 处理实现
 *
 * @author zhangcs
 */
@Slf4j
public final class MybatisSqlExtractor implements SqlExtractor {

    @Override
    public String name() {
        return "mybatis";
    }

    @Override
    public SqlExtractionResult extract(SqlExtractorContext context) {
        List<String> diagnostics = new ArrayList<>();
        List<SqlStatement> statements = new ArrayList<>();

        Set<JavaSootClass> mappers = MapperResolver.resolve(context.view());
        if (mappers.isEmpty()) {
            return SqlExtractionResult.empty();
        }

        Map<String, XmlMapper> xmlMappers = xmlMappers(context.source());
        for (JavaSootClass mapper : mappers) {
            extractMapper(context, mapper, xmlMappers, statements, diagnostics);
        }
        return new SqlExtractionResult(statements, diagnostics);
    }

    private Map<String, XmlMapper> xmlMappers(Path source) {
        return FileUtils.findXmlFiles(source).stream()
                .map(XmlMapperParser::parse)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(XmlMapper::namespace, mx -> mx));
    }

    private void extractMapper(
            SqlExtractorContext context,
            JavaSootClass mapper,
            Map<String, XmlMapper> xmlMappers,
            List<SqlStatement> statements,
            List<String> diagnostics) {

        String className = mapper.getName();
        for (JavaSootMethod method : mapper.getMethods()) {
            log.debug("{}#{}", className, method.getName());
            SqlStatement statement = extractStatement(context, method, xmlMappers);
            if (statement == null) {
                String message = "SQL解析失败: " + SqlExtractionSupport.statementId(method.getSignature());
                log.warn(message);
                diagnostics.add(message);
                continue;
            }
            statements.add(statement);
        }
    }

    private SqlStatement extractStatement(
            SqlExtractorContext context,
            JavaSootMethod method,
            Map<String, XmlMapper> xmlMappers) {

        MethodSignature signature = method.getSignature();
        String className = method.getDeclaringClassType().getFullyQualifiedName();
        String methodName = method.getName();

        SqlSourceType sourceType = SqlSourceType.UNKNOWN;
        SqlNode sqlNode = null;
        String rawSql = null;
        String sourceFile = null;
        StatementType statementType = null;

        Optional<AnnotationUsage> optional = methodMappingAnnotation(method);
        if (optional.isPresent()) {
            AnnotationUsage annotation = optional.get();
            if (isMappingProvider(annotation)) {
                ProviderSqlStatement statement = ProviderSqlResolver.resolve(
                        context.view(),
                        methodName,
                        annotation,
                        signature.getParameterTypes());
                rawSql = statement != null ? statement.sql() : "";
                sqlNode = SqlNodeBuilder.build(rawSql);
                sourceType = SqlSourceType.PROVIDER_SQL;
                sourceFile = className;
                statementType = providerStatementType(annotation);
            } else if (isMapping(annotation)) {
                rawSql = MappingResolver.resolve(annotation);
                sqlNode = SqlNodeBuilder.build(rawSql);
                sourceType = SqlSourceType.STATIC_SQL;
                sourceFile = className;
                statementType = annotationStatementType(annotation);
            }
        } else {
            XmlMapper xmlMapper = xmlMappers.get(className);
            if (xmlMapper != null) {
                MappedStatement statement = xmlMapper.getStatement(methodName);
                if (statement != null) {
                    rawSql = statement.rawSql();
                    sqlNode = SqlNodeBuilder.build(rawSql);
                    sourceType = SqlSourceType.XML_SQL;
                    sourceFile = xmlMapper.sourceFile();
                    statementType = statement.type();
                }
            }
        }

        if (sqlNode == null) {
            return null;
        }

        MethodSignature implSig = MapperImplBuilder.getImpl(method);
        Set<MethodSignature> callers = context.graph().callSourcesTo(implSig);
        List<String> callerNames = callers.stream()
                .map(ms -> ms.getDeclClassType().getFullyQualifiedName() + "#" + ms.getName())
                .toList();

        AnalyzeResult.Metadata metadata = SqlExtractionSupport.metadata(
                callerNames,
                List.of(name()),
                Collections.emptyMap());

        Type returnType = method.getReturnType();
        List<String> parameterTypes = method.getParameterTypes().stream().map(Object::toString).toList();
        SourceLocation location = SourceLocation.of(
                className,
                methodName,
                returnType.toString(),
                parameterTypes,
                sourceFile);

        return new SqlStatement(
                SqlExtractionSupport.statementId(method.getSignature()),
                statementType,
                sourceType,
                location,
                rawSql,
                sqlNode,
                parameterTargets(implSig),
                List.of(),
                metadata);
    }

    private List<MethodParameterRef> parameterTargets(MethodSignature signature) {
        int count = signature.getParameterCount();
        List<MethodParameterRef> targets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            targets.add(MethodParameterRef.of(signature, i));
        }
        return targets;
    }

    private StatementType providerStatementType(AnnotationUsage annotation) {
        if (isSelectProvider(annotation)) {
            return StatementType.SELECT;
        }
        if (isUpdateProvider(annotation)) {
            return StatementType.UPDATE;
        }
        if (isDeleteProvider(annotation)) {
            return StatementType.DELETE;
        }
        if (isInsertProvider(annotation)) {
            return StatementType.INSERT;
        }
        return null;
    }

    private StatementType annotationStatementType(AnnotationUsage annotation) {
        if (isSelect(annotation)) {
            return StatementType.SELECT;
        }
        if (isUpdate(annotation)) {
            return StatementType.UPDATE;
        }
        if (isDelete(annotation)) {
            return StatementType.DELETE;
        }
        return StatementType.INSERT;
    }

    private Optional<AnnotationUsage> methodMappingAnnotation(JavaSootMethod method) {
        if (method == null) {
            return Optional.empty();
        }
        Iterable<AnnotationUsage> annotations = method.getAnnotations();
        for (AnnotationUsage annotation : annotations) {
            if (isMapping(annotation)) {
                return Optional.of(annotation);
            }
        }
        return Optional.empty();
    }
}
