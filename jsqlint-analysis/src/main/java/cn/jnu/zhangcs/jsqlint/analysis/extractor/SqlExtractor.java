package cn.jnu.zhangcs.jsqlint.analysis.extractor;

/**
 * SQL 提取器接口.
 *
 * @author zhangcs
 */
public interface SqlExtractor {

    String name();

    SqlExtractionResult extract(SqlExtractorContext context);
}
