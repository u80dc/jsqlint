package cn.jnu.zhangcs.jsqlint.analysis.extractor;

import cn.jnu.zhangcs.jsqlint.analysis.Analyzer;

/**
 * 框架特定的 SQL 提取入口。
 * <p>
 * 每种数据访问方式（MyBatis、JDBC、JPA、Spring JDBC、资源文件等）实现该接口，
 * 在 {@link Analyzer#analyze()} 中被依次调用以收集 SQL 定义。
 * </p>
 *
 * @author zhangcs
 */
public interface SqlExtractor {

    /**
     * 提取器名称，用于诊断信息与日志标识。
     *
     * @return 提取器名称
     */
    String name();

    /**
     * 在给定上下文中执行 SQL 提取。
     *
     * @param context 共享分析上下文（视图、调用图、源路径等）
     * @return 提取结果
     */
    SqlExtractionResult extract(SqlExtractorContext context);
}
