package cn.jnu.zhangcs.jsqlint.analysis.parameter.infer;

import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于参数传播路径推断目标参数可能值。
 *
 * <p>该类消费 {@link ParameterTraceResult}。每条 {@link CallTrace} 会独立推断出
 * 一种可能值，并通过 {@link InferredParameterValue#propagationPath()} 保留该值对应的
 * 完整传播路径。
 *
 * @author zhangcs
 */
public final class ParameterValueInferencer {

    private final ExternalValueGenerator externalValueGenerator;

    public ParameterValueInferencer() {
        this(new RandomExternalValueGenerator());
    }

    public ParameterValueInferencer(ExternalValueGenerator externalValueGenerator) {
        this.externalValueGenerator = Objects.requireNonNull(externalValueGenerator, "externalValueGenerator");
    }

    /**
     * 根据参数追踪结果推断可能值。
     *
     * @param traceResult {@link ParameterTraceAnalyzer#analyze} 的结果
     * @return 参数可能值推断结果
     */
    public ParameterValueInferenceResult infer(ParameterTraceResult traceResult) {
        Objects.requireNonNull(traceResult, "traceResult");

        List<String> diagnostics = new ArrayList<>(traceResult.diagnostics());
        List<InferredParameterValue> values = new ArrayList<>();
        for (CallTrace trace : traceResult.traces()) {
            ValueModel targetValue = targetValueModel(trace, diagnostics);
            InferredValue inferred = inferValue(targetValue, diagnostics, new LinkedHashMap<>());
            values.add(new InferredParameterValue(
                    traceResult.target(),
                    inferred.value(),
                    inferred.displayValue(),
                    inferred.valueType(),
                    inferred.origin(),
                    targetValue,
                    trace.rootValue(),
                    trace));
        }

        if (traceResult.traces().isEmpty()) {
            diagnostics.add("No propagation path to infer value for " + traceResult.target().displayName());
        }
        return new ParameterValueInferenceResult(traceResult.target(), values, diagnostics);
    }

    private ValueModel targetValueModel(CallTrace trace, List<String> diagnostics) {
        ValueModel current = trace.rootValue();
        List<TraceStep> steps = trace.steps();
        for (int i = steps.size() - 1; i >= 0; i--) {
            TraceStep step = steps.get(i);
            current = applyUpstreamValue(step, current, diagnostics);
        }
        return current;
    }

    private ValueModel applyUpstreamValue(TraceStep step, ValueModel upstreamValue, List<String> diagnostics) {
        Set<ValueModel.ParameterValue> dependencies = step.argument().parameterDependencies();
        if (dependencies.isEmpty()) {
            return step.argument();
        }
        if (dependencies.size() > 1) {
            diagnostics.add("Multiple parameter dependencies in one step are kept as-is: " + step.summary());
            return step.argument();
        }
        int parameterIndex = dependencies.iterator().next().index();
        return substituteParameter(step.argument(), parameterIndex, upstreamValue);
    }

    private ValueModel substituteParameter(ValueModel value, int parameterIndex, ValueModel replacement) {
        return switch (value) {
            case ValueModel.ParameterValue parameterValue ->
                    parameterValue.index() == parameterIndex ? replacement : parameterValue;
            case ValueModel.ConcatValue concatValue -> new ValueModel.ConcatValue(concatValue.parts().stream()
                    .map(part -> substituteParameter(part, parameterIndex, replacement))
                    .toList());
            case ValueModel.ArrayValue arrayValue -> {
                Map<Integer, ValueModel> elements = new LinkedHashMap<>();
                for (Map.Entry<Integer, ValueModel> entry : arrayValue.elements().entrySet()) {
                    elements.put(entry.getKey(), substituteParameter(entry.getValue(), parameterIndex, replacement));
                }
                yield new ValueModel.ArrayValue(arrayValue.type(), elements);
            }
            case ValueModel.ObjectValue objectValue -> {
                Map<String, ValueModel> fields = new LinkedHashMap<>();
                for (Map.Entry<String, ValueModel> entry : objectValue.fields().entrySet()) {
                    fields.put(entry.getKey(), substituteParameter(entry.getValue(), parameterIndex, replacement));
                }
                yield new ValueModel.ObjectValue(objectValue.type(), fields);
            }
            case ValueModel.InvokeValue invokeValue -> {
                ValueModel receiver = invokeValue.receiver() == null
                        ? null
                        : substituteParameter(invokeValue.receiver(), parameterIndex, replacement);
                List<ValueModel> args = invokeValue.arguments().stream()
                        .map(arg -> substituteParameter(arg, parameterIndex, replacement))
                        .toList();
                yield new ValueModel.InvokeValue(invokeValue.method(), receiver, args);
            }
            case ValueModel.FieldValue fieldValue -> {
                ValueModel base = fieldValue.base() == null
                        ? null
                        : substituteParameter(fieldValue.base(), parameterIndex, replacement);
                yield new ValueModel.FieldValue(fieldValue.fieldName(), base, fieldValue.type());
            }
            case ValueModel.CastValue castValue -> new ValueModel.CastValue(
                    castValue.targetType(),
                    substituteParameter(castValue.value(), parameterIndex, replacement));
            case ValueModel.UnknownValue unknownValue -> unknownValue;
            case ValueModel.LocalValue localValue -> localValue;
            case ValueModel.LiteralValue literalValue -> literalValue;
        };
    }

    private InferredValue inferValue(
            ValueModel value,
            List<String> diagnostics,
            Map<ExternalParameterKey, Object> externalSamples) {
        return switch (value) {
            case ValueModel.ParameterValue parameterValue -> inferExternalInput(parameterValue, externalSamples);
            case ValueModel.LiteralValue literalValue -> inferLiteral(literalValue);
            case ValueModel.ConcatValue concatValue -> inferConcat(concatValue, diagnostics, externalSamples);
            case ValueModel.ArrayValue arrayValue -> inferArray(arrayValue, diagnostics, externalSamples);
            case ValueModel.ObjectValue objectValue -> inferObject(objectValue, diagnostics, externalSamples);
            case ValueModel.InvokeValue invokeValue -> inferInvoke(invokeValue, diagnostics, externalSamples);
            case ValueModel.FieldValue fieldValue -> inferField(fieldValue, diagnostics, externalSamples);
            case ValueModel.CastValue castValue -> inferValue(castValue.value(), diagnostics, externalSamples)
                    .withType(castValue.targetType(), InferredValueOrigin.INVOCATION);
            case ValueModel.LocalValue localValue ->
                    unknown(localValue, "Unresolved local value: " + localValue.name());
            case ValueModel.UnknownValue unknownValue -> unknown(unknownValue, unknownValue.reason());
        };
    }

    private InferredValue inferExternalInput(
            ValueModel.ParameterValue parameterValue,
            Map<ExternalParameterKey, Object> externalSamples) {

        ExternalParameterKey key = new ExternalParameterKey(parameterValue.index(), parameterValue.name(), parameterValue.type());
        Object generated = externalSamples.computeIfAbsent(
                key,
                ignored -> externalValueGenerator.generate(parameterValue.type(), parameterValue.name()));
        return new InferredValue(
                generated,
                display(generated),
                parameterValue.type(),
                InferredValueOrigin.EXTERNAL_INPUT);
    }

    private InferredValue inferLiteral(ValueModel.LiteralValue literalValue) {
        Object parsed = parseLiteral(literalValue.value(), literalValue.type());
        return new InferredValue(parsed, display(parsed), literalValue.type(), InferredValueOrigin.LITERAL);
    }

    private InferredValue inferConcat(
            ValueModel.ConcatValue concatValue,
            List<String> diagnostics,
            Map<ExternalParameterKey, Object> externalSamples) {
        StringBuilder builder = new StringBuilder();
        for (ValueModel part : concatValue.parts()) {
            InferredValue inferred = inferValue(part, diagnostics, externalSamples);
            builder.append(inferred.value() == null ? "null" : inferred.value());
        }
        String value = builder.toString();
        return new InferredValue(value, display(value), "java.lang.String", InferredValueOrigin.CONCAT);
    }

    private InferredValue inferArray(
            ValueModel.ArrayValue arrayValue,
            List<String> diagnostics,
            Map<ExternalParameterKey, Object> externalSamples) {
        Map<Integer, Object> elements = new LinkedHashMap<>();
        for (Map.Entry<Integer, ValueModel> entry : arrayValue.elements().entrySet()) {
            elements.put(entry.getKey(), inferValue(entry.getValue(), diagnostics, externalSamples).value());
        }
        return new InferredValue(elements, display(elements), arrayValue.type(), InferredValueOrigin.ARRAY);
    }

    private InferredValue inferObject(
            ValueModel.ObjectValue objectValue,
            List<String> diagnostics,
            Map<ExternalParameterKey, Object> externalSamples) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<String, ValueModel> entry : objectValue.fields().entrySet()) {
            fields.put(entry.getKey(), inferValue(entry.getValue(), diagnostics, externalSamples).value());
        }
        return new InferredValue(fields, display(fields), objectValue.type(), InferredValueOrigin.OBJECT);
    }

    private InferredValue inferInvoke(
            ValueModel.InvokeValue invokeValue,
            List<String> diagnostics,
            Map<ExternalParameterKey, Object> externalSamples) {
        String className = invokeValue.method().getDeclClassType().getFullyQualifiedName();
        String methodName = invokeValue.method().getName();

        if (isStringConcatInvocation(className, methodName)) {
            StringBuilder builder = new StringBuilder();
            for (ValueModel argument : invokeValue.arguments()) {
                Object value = inferValue(argument, diagnostics, externalSamples).value();
                builder.append(value == null ? "null" : value);
            }
            String converted = builder.toString();
            return new InferredValue(converted, display(converted), "java.lang.String", InferredValueOrigin.CONCAT);
        }

        if ("valueOf".equals(methodName) && !invokeValue.arguments().isEmpty()) {
            InferredValue argument = inferValue(invokeValue.arguments().get(0), diagnostics, externalSamples);
            Object converted = convertValueOf(className, argument.value());
            return new InferredValue(converted, display(converted), className, InferredValueOrigin.INVOCATION);
        }

        if ("toString".equals(methodName) && invokeValue.receiver() != null) {
            Object receiver = inferValue(invokeValue.receiver(), diagnostics, externalSamples).value();
            String converted = String.valueOf(receiver);
            return new InferredValue(converted, display(converted), "java.lang.String", InferredValueOrigin.INVOCATION);
        }

        if ("trim".equals(methodName)
                && "java.lang.String".equals(className)
                && invokeValue.receiver() != null) {
            Object receiver = inferValue(invokeValue.receiver(), diagnostics, externalSamples).value();
            String converted = String.valueOf(receiver).trim();
            return new InferredValue(converted, display(converted), "java.lang.String", InferredValueOrigin.INVOCATION);
        }

        if (isGetter(methodName) && invokeValue.receiver() instanceof ValueModel.ObjectValue objectValue) {
            String fieldName = getterPropertyName(methodName);
            ValueModel fieldValue = objectValue.fields().get(fieldName);
            if (fieldValue != null) {
                return inferValue(fieldValue, diagnostics, externalSamples);
            }
        }

        String args = invokeValue.arguments().stream()
                .map(arg -> inferValue(arg, diagnostics, externalSamples).displayValue())
                .collect(Collectors.joining(", "));
        String expression = className + "." + methodName + "(" + args + ")";
        diagnostics.add("Kept method invocation as expression: " + expression);
        return new InferredValue(expression, expression, invokeValue.method().getType().toString(), InferredValueOrigin.INVOCATION);
    }

    private boolean isStringConcatInvocation(String className, String methodName) {
        return methodName != null
                && methodName.startsWith("makeConcat")
                && (className == null
                || className.isBlank()
                || className.equals("java.lang.invoke.StringConcatFactory")
                || className.equals("sootup.dummy.InvokeDynamic"));
    }

    private boolean isGetter(String methodName) {
        return getterPropertyName(methodName) != null;
    }

    private String getterPropertyName(String methodName) {
        if (methodName == null || methodName.length() <= 3) {
            return null;
        }
        if (methodName.startsWith("get") && Character.isUpperCase(methodName.charAt(3))) {
            return Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
        }
        if (methodName.startsWith("is") && methodName.length() > 2 && Character.isUpperCase(methodName.charAt(2))) {
            return Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
        }
        return null;
    }

    private InferredValue inferField(
            ValueModel.FieldValue fieldValue,
            List<String> diagnostics,
            Map<ExternalParameterKey, Object> externalSamples) {
        if (fieldValue.base() instanceof ValueModel.ParameterValue) {
            Object generated = externalValueGenerator.generate(fieldValue.type(), fieldValue.fieldName());
            return new InferredValue(generated, display(generated), fieldValue.type(), InferredValueOrigin.EXTERNAL_INPUT);
        }
        diagnostics.add("Field value kept as expression: " + fieldValue.display());
        return new InferredValue(fieldValue.display(), fieldValue.display(), fieldValue.type(), InferredValueOrigin.FIELD);
    }

    private InferredValue unknown(ValueModel value, String reason) {
        return new InferredValue(value.display(), value.display(), "unknown", InferredValueOrigin.UNKNOWN);
    }

    private Object parseLiteral(String raw, String type) {
        if (raw == null || "null".equals(raw)) {
            return null;
        }

        String literal = raw.trim();
        String normalizedType = type == null ? "" : type.trim();
        try {
            return switch (normalizedType) {
                case "java.lang.String", "String" -> literal;
                case "boolean", "java.lang.Boolean" -> Boolean.parseBoolean(literal);
                case "byte", "java.lang.Byte" -> Byte.parseByte(stripNumericSuffix(literal));
                case "short", "java.lang.Short" -> Short.parseShort(stripNumericSuffix(literal));
                case "int", "java.lang.Integer" -> Integer.parseInt(stripNumericSuffix(literal));
                case "long", "java.lang.Long" -> Long.parseLong(stripNumericSuffix(literal));
                case "float", "java.lang.Float" -> Float.parseFloat(stripNumericSuffix(literal));
                case "double", "java.lang.Double" -> Double.parseDouble(stripNumericSuffix(literal));
                case "char", "java.lang.Character" -> literal.isEmpty() ? '\0' : literal.charAt(0);
                case "java.math.BigDecimal", "BigDecimal" -> new BigDecimal(stripNumericSuffix(literal));
                default -> parseUntypedLiteral(literal);
            };
        } catch (NumberFormatException ignored) {
            return literal;
        }
    }

    private Object parseUntypedLiteral(String literal) {
        if (literal.matches("-?\\d+[lL]")) {
            return Long.parseLong(stripNumericSuffix(literal));
        }
        if (literal.matches("-?\\d+")) {
            return Integer.parseInt(literal);
        }
        if (literal.matches("-?\\d+\\.\\d+[fFdD]?")) {
            return Double.parseDouble(stripNumericSuffix(literal));
        }
        if ("true".equalsIgnoreCase(literal) || "false".equalsIgnoreCase(literal)) {
            return Boolean.parseBoolean(literal);
        }
        return literal;
    }

    private Object convertValueOf(String className, Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        try {
            return switch (className) {
                case "java.lang.String" -> text;
                case "java.lang.Boolean" -> Boolean.parseBoolean(text);
                case "java.lang.Byte" -> Byte.parseByte(stripNumericSuffix(text));
                case "java.lang.Short" -> Short.parseShort(stripNumericSuffix(text));
                case "java.lang.Integer" -> Integer.parseInt(stripNumericSuffix(text));
                case "java.lang.Long" -> Long.parseLong(stripNumericSuffix(text));
                case "java.lang.Float" -> Float.parseFloat(stripNumericSuffix(text));
                case "java.lang.Double" -> Double.parseDouble(stripNumericSuffix(text));
                default -> value;
            };
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private String stripNumericSuffix(String literal) {
        return literal.replaceFirst("[lLfFdD]$", "");
    }

    private String display(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String string) {
            return '"' + string + '"';
        }
        if (value instanceof Object[] array) {
            return java.util.Arrays.deepToString(array);
        }
        return String.valueOf(value);
    }

    private record InferredValue(
            Object value,
            String displayValue,
            String valueType,
            InferredValueOrigin origin) {

        private InferredValue withType(String type, InferredValueOrigin origin) {
            return new InferredValue(value, displayValue, type, origin);
        }
    }

    private record ExternalParameterKey(int index, String name, String type) {
    }
}
