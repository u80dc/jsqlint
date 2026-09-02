package cn.jnu.zhangcs.jsqlint.analysis.extractor;

import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.MethodParameterRef;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ValueModel;

import java.util.List;
import java.util.Objects;

/**
 * SQL 字符串的取值表示。
 *
 * <p>当 SQL 语句由字符串拼接或参数注入构成时，该记录保存原始字符串形式、
 * 对应的值模型以及解析过程中的诊断信息。</p>
 *
 * @param rawSql      原始 SQL 文本
 * @param model       值来源模型，用于参数溯源与值推断
 * @param parameters  SQL 依赖的外部参数引用，供参数轨迹追踪使用
 * @param diagnostics 解析过程中产生的诊断信息
 * @author zhangcs
 */
public record SqlValue(
        String rawSql,
        ValueModel model,
        List<MethodParameterRef> parameters,
        List<String> diagnostics) {

    /**
     * 规整集合字段，避免调用方处理 null。
     */
    public SqlValue {
        Objects.requireNonNull(rawSql, "rawSql");
        Objects.requireNonNull(model, "model");
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /**
     * 兼容无外部参数场景的构造器。
     */
    public SqlValue(String rawSql, ValueModel model, List<String> diagnostics) {
        this(rawSql, model, List.of(), diagnostics);
    }
}
