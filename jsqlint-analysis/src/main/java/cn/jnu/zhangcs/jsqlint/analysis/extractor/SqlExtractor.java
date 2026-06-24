package cn.jnu.zhangcs.jsqlint.analysis.extractor;

/**
 * Framework-specific SQL extraction entry point.
 */
public interface SqlExtractor {

    String name();

    SqlExtractionResult extract(SqlExtractorContext context);
}
