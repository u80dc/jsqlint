package cn.jnu.zhangcs.jsqlint.analysis.parameter.trace;

import sootup.core.signatures.MethodSignature;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 调用点处实际参数的结构化表示.
 *
 * <p>此模型特意记录参数的形状和依赖关系，而不是尝试确定所有可能的运行时值。后续的值推断可以使用同一树，而无需重新运行调用链搜索.
 *
 * @author zhangcs
 */
public sealed interface ValueModel {

    private static void collectParameters(ValueModel value, Set<ParameterValue> result) {
        if (value == null) {
            return;
        }
        switch (value) {
            case ParameterValue parameterValue -> result.add(parameterValue);
            case ConcatValue concatValue -> concatValue.parts.forEach(part -> collectParameters(part, result));
            case ArrayValue arrayValue -> arrayValue.elements.values().forEach(part -> collectParameters(part, result));
            case InvokeValue invokeValue -> {
                collectParameters(invokeValue.receiver, result);
                invokeValue.arguments.forEach(arg -> collectParameters(arg, result));
            }
            case ObjectValue objectValue ->
                    objectValue.fields.values().forEach(part -> collectParameters(part, result));
            case FieldValue fieldValue -> collectParameters(fieldValue.base, result);
            case CastValue castValue -> collectParameters(castValue.value, result);
            case MultiValue multiValue -> multiValue.alternatives.forEach(part -> collectParameters(part, result));
            case UnknownValue ignored -> {
            }
            case LocalValue ignored -> {
            }
            case LiteralValue ignored -> {
            }
        }
    }

    String display();

    default Set<ParameterValue> parameterDependencies() {
        Set<ParameterValue> result = new LinkedHashSet<>();
        collectParameters(this, result);
        return Collections.unmodifiableSet(result);
    }

    record ObjectValue(String type, Map<String, ValueModel> fields) implements ValueModel {
        public ObjectValue {
            type = type == null || type.isBlank() ? "object" : type;
            fields = fields == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        @Override
        public String display() {
            if (fields.isEmpty()) {
                return "new " + type + "{}";
            }
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, ValueModel> entry : fields.entrySet()) {
                parts.add(entry.getKey() + "=" + entry.getValue().display());
            }
            return "new " + type + "{" + String.join(", ", parts) + "}";
        }
    }

    record UnknownValue(String reason) implements ValueModel {
        public UnknownValue {
            reason = reason == null || reason.isBlank() ? "unknown" : reason;
        }

        @Override
        public String display() {
            return "unknown(" + reason + ")";
        }
    }

    record LocalValue(String name, String type) implements ValueModel {
        public LocalValue {
            name = name == null || name.isBlank() ? "local" : name;
            type = type == null || type.isBlank() ? "unknown" : type;
        }

        @Override
        public String display() {
            return name + ":" + type;
        }
    }

    record ParameterValue(int index, String name, String type) implements ValueModel {
        public ParameterValue {
            name = name == null || name.isBlank() ? "param" + index : name;
            type = type == null || type.isBlank() ? "unknown" : type;
        }

        @Override
        public String display() {
            return name + "(@param" + index + ")";
        }
    }

    record LiteralValue(String value, String type) implements ValueModel {
        public LiteralValue {
            value = value == null ? "null" : value;
            type = type == null || type.isBlank() ? "unknown" : type;
        }

        @Override
        public String display() {
            return value;
        }
    }

    record ConcatValue(List<ValueModel> parts) implements ValueModel {
        public ConcatValue {
            parts = parts == null ? List.of() : List.copyOf(parts);
        }

        @Override
        public String display() {
            return parts.stream().map(ValueModel::display).collect(Collectors.joining(" + "));
        }
    }

    record ArrayValue(String type, Map<Integer, ValueModel> elements) implements ValueModel {
        public ArrayValue {
            type = type == null || type.isBlank() ? "array" : type;
            elements = elements == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(elements));
        }

        @Override
        public String display() {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<Integer, ValueModel> entry : elements.entrySet()) {
                parts.add(entry.getKey() + "=" + entry.getValue().display());
            }
            return type + "{" + String.join(", ", parts) + "}";
        }
    }

    record InvokeValue(
            MethodSignature method,
            ValueModel receiver,
            List<ValueModel> arguments) implements ValueModel {

        public InvokeValue {
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
        }

        @Override
        public String display() {
            String owner = receiver == null
                    ? method.getDeclClassType().getFullyQualifiedName()
                    : receiver.display();
            String args = arguments.stream().map(ValueModel::display).collect(Collectors.joining(", "));
            return owner + "." + method.getName() + "(" + args + ")";
        }
    }

    record FieldValue(String fieldName, ValueModel base, String type) implements ValueModel {
        public FieldValue {
            fieldName = fieldName == null || fieldName.isBlank() ? "field" : fieldName;
            type = type == null || type.isBlank() ? "unknown" : type;
        }

        @Override
        public String display() {
            String prefix = base == null ? "" : base.display() + ".";
            return prefix + fieldName;
        }
    }

    record CastValue(String targetType, ValueModel value) implements ValueModel {
        public CastValue {
            targetType = targetType == null || targetType.isBlank() ? "unknown" : targetType;
            value = value == null ? new UnknownValue("cast operand") : value;
        }

        @Override
        public String display() {
            return "(" + targetType + ") " + value.display();
        }
    }

    /**
     * 同一变量在多条控制流路径上被赋予不同值的集合。
     *
     * <p>典型形态是 if/else 各分支对同一局部变量赋值后在调用点使用：
     * 两处定义都到达使用点，无法用"线性最近定义"取舍时，以候选集表示。
     * 推断阶段对每个候选独立求值，产生多条可能值。</p>
     */
    record MultiValue(List<ValueModel> alternatives) implements ValueModel {
        public MultiValue {
            alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        }

        @Override
        public String display() {
            return alternatives.stream()
                    .map(ValueModel::display)
                    .collect(Collectors.joining(" | ", "{", "}"));
        }
    }
}
