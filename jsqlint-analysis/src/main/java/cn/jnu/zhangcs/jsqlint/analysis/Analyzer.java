package cn.jnu.zhangcs.jsqlint.analysis;

import cn.jnu.zhangcs.jsqlint.analysis.extractor.*;
import cn.jnu.zhangcs.jsqlint.analysis.ibatis.MybatisSqlExtractor;
import cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.resolver.MapperResolver;
import cn.jnu.zhangcs.jsqlint.analysis.ibatis.impl.MapperImplBuilder;
import cn.jnu.zhangcs.jsqlint.analysis.jdbc.NativeJdbcSqlExtractor;
import cn.jnu.zhangcs.jsqlint.analysis.jpa.JpaSqlExtractor;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.ParameterAnalysisService;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ParameterTraceAnalyzer;
import cn.jnu.zhangcs.jsqlint.analysis.pipeline.SqlStatementAssembler;
import cn.jnu.zhangcs.jsqlint.analysis.spring.SpringJdbcSqlExtractor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import sootup.callgraph.EnhancedCallGraph;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.inputlocation.FileType;
import sootup.core.model.SourceType;
import sootup.core.util.PathUtils;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JrtFileSystemAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation;
import sootup.java.core.JavaSootClass;
import sootup.java.core.views.JavaView;
import sootup.java.core.views.MutableJavaView;
import sootup.java.frontend.inputlocation.JavaSourcePathAnalysisInputLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.jnu.zhangcs.jsqlint.analysis.util.FileUtils.findJars;

/**
 * 分析器
 *
 * @author zhangcs
 */
@Slf4j
public class Analyzer {

    private final List<AnalysisInputLocation> locations = new ArrayList<>();

    /**
     * 程序文件
     */
    @Getter
    private final @NonNull Path source;

    /**
     * 依赖文件
     */
    @Getter
    private final @Nullable Path library;

    private final JavaView view;

    private final EnhancedCallGraph graph;

    private final ParameterTraceAnalyzer analyzer;

    private final ParameterAnalysisService parameterAnalysisService;

    public Analyzer(@NonNull Path source, @Nullable Path library) {
        this.source = source;
        this.library = library;
        loadImplClasses(source);
        loadLibrary(library);
        loadSource(source);
        view = new MutableJavaView(locations);
        graph = EnhancedCallGraph.initialize(view);
        analyzer = new ParameterTraceAnalyzer(view, graph);
        parameterAnalysisService = new ParameterAnalysisService(analyzer);
    }

    /**
     * 生成并加载Mapper接口实现类
     *
     * @param source 应用
     */
    private void loadImplClasses(Path source) {
        JavaView originalView = new JavaView(List.of(PathBasedAnalysisInputLocation.create(source, SourceType.Application)));
        Set<JavaSootClass> mappers = MapperResolver.resolve(originalView);
        if (mappers.isEmpty()) {
            return;
        }
        Set<Path> proxies = MapperImplBuilder.newInstance(mappers);
        if (proxies.isEmpty()) {
            return;
        }
        String proxiesPath = String.join(";", proxies.stream()
                .map(Path::getParent)
                .map(Path::toAbsolutePath)
                .map(Path::toString)
                .collect(Collectors.toSet()));
        AnalysisInputLocation location = new JavaClassPathAnalysisInputLocation(proxiesPath);
        locations.add(location);
    }

    /**
     * 分析
     * @return 返回分析结果
     */
    public @NonNull AnalyzeResult analyze() {
        List<String> analyzeDiagnostics = new ArrayList<>();
        List<SqlStatement> extractedStatements = new ArrayList<>();
        List<AnalyzeResult.SqlStatementResult> statements = new ArrayList<>();
        SqlExtractorContext context = new SqlExtractorContext(source, view, graph);
        SqlStatementAssembler assembler = new SqlStatementAssembler(parameterAnalysisService);
        List<SqlExtractor> extractors = List.of(
                new SqlResourceExtractor(),
                new MybatisSqlExtractor(),
                new NativeJdbcSqlExtractor(),
                new SpringJdbcSqlExtractor(),
                new JpaSqlExtractor());

        for (SqlExtractor extractor : extractors) {
            try {
                SqlExtractionResult result = extractor.extract(context);
                extractedStatements.addAll(result.statements());
                analyzeDiagnostics.addAll(result.diagnostics());
            } catch (RuntimeException e) {
                String message = extractor.name() + " SQL解析失败: " + e.getMessage();
                log.warn(message, e);
                analyzeDiagnostics.add(message);
            }
        }

        for (SqlStatement extracted : extractedStatements) {
            statements.add(assembler.assemble(extracted));
        }

        if (statements.isEmpty()) {
            return AnalyzeResult.failed("未解析到SQL定义");
        }
        return AnalyzeResult.success(statements, analyzeDiagnostics);
    }

    /**
     * 加载目标程序源文件
     *
     * @param source 文目标程序源件
     */
    protected void loadSource(Path source) {
        if (Files.isDirectory(source)) {
            locations.add(PathBasedAnalysisInputLocation.create(source.toAbsolutePath(), SourceType.Application));
            return;
        }
        if (PathUtils.hasExtension(source, FileType.JAVA)) {
            // .java
            locations.add(new JavaSourcePathAnalysisInputLocation(source.toAbsolutePath().toString()));
        } else if (PathUtils.hasExtension(source, FileType.CLASS)) {
            // .class file
            locations.add(new JavaClassPathAnalysisInputLocation(source.toAbsolutePath().toString()));
        } else {
            // .jar、.war、dir
            locations.add(PathBasedAnalysisInputLocation.create(source, SourceType.Application));
        }
    }

    /**
     * 加载依赖文件
     *
     * @param library 依赖文件路径
     */
    protected void loadLibrary(Path library) {
        // 默认都是Java9+
        locations.add(new JrtFileSystemAnalysisInputLocation());
        // 其他依赖包
        if (library == null || !Files.exists(library)) {
            return;
        }
        List<Path> jars = findJars(library);
        if (jars.isEmpty()) {
            locations.add(PathBasedAnalysisInputLocation.create(library, SourceType.Library));
            return;
        }
        for (var jar : jars) {
            locations.add(PathBasedAnalysisInputLocation.create(jar.toAbsolutePath(), SourceType.Library));
        }
    }

}
