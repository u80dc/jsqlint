package cn.jnu.zhangcs.jsqlint.analysis.parameter.infer;

import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.CallTrace;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.MethodParameterRef;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ParameterTraceResult;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ValueModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 将通用 {@link ValueModel} 解析为字符串值，不依赖任何 SQL 框架。
 *
 * <p>解析优先级：先尝试直接拼接模型中的字面量（如字符串常量、toString/valueOf 调用），
 * 失败后再交给 {@link ParameterValueInferencer} 推断，并附带诊断信息。</p>
 *
 * @author zhangcs
 */
public final class ValueModelStringResolver {

    private final ParameterValueInferencer inferencer;

    /**
     * 创建默认实例，使用内置的采样式外部值生成器。
     */
    public ValueModelStringResolver() {
        this(new ParameterValueInferencer(ValueModelStringResolver::sampleExternalValue));
    }

    /**
     * 使用自定义推断器创建实例。
     *
     * @param inferencer 参数值推断器
     */
    public ValueModelStringResolver(ParameterValueInferencer inferencer) {
        this.inferencer = Objects.requireNonNull(inferencer, "inferencer");
    }

    /**
     * 将值模型解析为字符串。
     *
     * @param model  值模型，允许为 null
     * @param target 目标方法参数引用，用于构造推断上下文
     * @return 解析出的字符串及诊断信息；无法解析时返回空
     */
    public Optional<ResolvedString> resolve(ValueModel model, MethodParameterRef target) {
        Objects.requireNonNull(target, "target");
        Optional<String> rawString = rawString(model);
        if (rawString.isPresent() && !rawString.get().isBlank()) {
            return Optional.of(new ResolvedString(rawString.get(), List.of()));
        }

        ParameterTraceResult traceResult = new ParameterTraceResult(
                target,
                List.of(new CallTrace(List.of(), model)),
                List.of());
        ParameterValueInferenceResult inference = inferencer.infer(traceResult);
        List<String> diagnostics = new ArrayList<>(inference.diagnostics());

        for (InferredParameterValue value : inference.possibleValues()) {
            if (value.value() instanceof String rawSql && !rawSql.isBlank()) {
                return Optional.of(new ResolvedString(rawSql, diagnostics));
            }
        }

        diagnostics.add("Unable to resolve string value: " + (model == null ? "<null>" : model.display()));
        return Optional.empty();
    }

    /**
     * 直接从值模型提取字符串，不触发推断。
     *
     * <p>支持字面量、拼接、强转、外部参数采样、toString/valueOf 调用；
     * 其余模型类型返回空。</p>
     *
     * @param model 值模型，允许为 null
     * @return 可直接确定的字符串；无法确定时返回空
     */
    public static Optional<String> rawString(ValueModel model) {
        if (model == null) {
            return Optional.empty();
        }
        return switch (model) {
            case ValueModel.LiteralValue literal -> Optional.ofNullable(literal.value());
            case ValueModel.ConcatValue concat -> rawConcat(concat);
            case ValueModel.CastValue cast -> rawString(cast.value());
            case ValueModel.ParameterValue parameter ->
                    Optional.of(String.valueOf(sampleExternalValue(parameter.type(), parameter.name())));
            case ValueModel.InvokeValue invoke -> rawInvoke(invoke);
            case ValueModel.MultiValue multi -> rawMulti(multi);
            case ValueModel.FieldValue ignored -> Optional.empty();
            case ValueModel.ArrayValue ignored -> Optional.empty();
            case ValueModel.ObjectValue ignored -> Optional.empty();
            case ValueModel.LocalValue ignored -> Optional.empty();
            case ValueModel.UnknownValue ignored -> Optional.empty();
        };
    }

    /**
     * 分支候选集的字符串解析：所有候选都能直接确定字符串时拼接呈现（带分隔标记），
     * 任一候选无法确定则整体失败，交由推断路径处理。
     */
    private static Optional<String> rawMulti(ValueModel.MultiValue multi) {
        if (multi.alternatives().isEmpty()) {
            return Optional.empty();
        }
        StringBuilder builder = new StringBuilder();
        for (ValueModel alternative : multi.alternatives()) {
            Optional<String> part = rawString(alternative);
            if (part.isEmpty()) {
                return Optional.empty();
            }
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append(part.get());
        }
        return Optional.of(builder.toString());
    }

    /**
     * 拼接模型的各部分字符串，任一部分无法确定则整体失败。
     */
    private static Optional<String> rawConcat(ValueModel.ConcatValue concat) {
        StringBuilder builder = new StringBuilder();
        for (ValueModel part : concat.parts()) {
            Optional<String> value = rawString(part);
            if (value.isEmpty()) {
                return Optional.empty();
            }
            builder.append(value.get());
        }
        return Optional.of(builder.toString());
    }

    /**
     * 处理 toString/valueOf 等可透明穿透的方法调用。
     */
    private static Optional<String> rawInvoke(ValueModel.InvokeValue invoke) {
        String className = invoke.method().getDeclClassType().getFullyQualifiedName();
        String methodName = invoke.method().getName();

        if ("toString".equals(methodName) && invoke.receiver() != null) {
            return rawString(invoke.receiver());
        }
        if ("valueOf".equals(methodName)
                && "java.lang.String".equals(className)
                && !invoke.arguments().isEmpty()) {
            return rawString(invoke.arguments().getFirst());
        }
        return Optional.empty();
    }

    /**
     * 为外部输入参数生成采样值：数值类型返回 1，布尔返回 true，其余返回参数名。
     */
    private static Object sampleExternalValue(String type, String name) {
        return switch (type) {
            case "byte", "java.lang.Byte", "short", "java.lang.Short", "int", "java.lang.Integer" -> 1;
            case "long", "java.lang.Long" -> 1L;
            case "float", "java.lang.Float" -> 1.0F;
            case "double", "java.lang.Double" -> 1.0D;
            case "boolean", "java.lang.Boolean" -> true;
            default -> name == null || name.isBlank() ? "param" : name;
        };
    }

    /**
     * 解析结果。
     *
     * @param value       解析出的字符串
     * @param diagnostics 推断过程中的诊断信息
     */
    public record ResolvedString(String value, List<String> diagnostics) {

        public ResolvedString {
            Objects.requireNonNull(value, "value");
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }
}
