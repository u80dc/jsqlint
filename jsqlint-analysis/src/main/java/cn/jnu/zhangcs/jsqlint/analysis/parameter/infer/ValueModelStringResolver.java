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
 * Resolves a generic {@link ValueModel} to a string value without depending on a SQL framework.
 */
public final class ValueModelStringResolver {

    private final ParameterValueInferencer inferencer;

    public ValueModelStringResolver() {
        this(new ParameterValueInferencer(ValueModelStringResolver::sampleExternalValue));
    }

    public ValueModelStringResolver(ParameterValueInferencer inferencer) {
        this.inferencer = Objects.requireNonNull(inferencer, "inferencer");
    }

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
            case ValueModel.FieldValue ignored -> Optional.empty();
            case ValueModel.ArrayValue ignored -> Optional.empty();
            case ValueModel.ObjectValue ignored -> Optional.empty();
            case ValueModel.LocalValue ignored -> Optional.empty();
            case ValueModel.UnknownValue ignored -> Optional.empty();
        };
    }

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

    public record ResolvedString(String value, List<String> diagnostics) {

        public ResolvedString {
            Objects.requireNonNull(value, "value");
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }
}
