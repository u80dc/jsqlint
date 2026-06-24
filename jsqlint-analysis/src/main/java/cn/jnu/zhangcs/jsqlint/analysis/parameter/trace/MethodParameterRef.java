package cn.jnu.zhangcs.jsqlint.analysis.parameter.trace;

import sootup.core.signatures.MethodSignature;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 方法形参引用。
 *
 * <p>该对象用于唯一描述某个方法上的一个参数，是参数追踪过程中的基本节点。
 * 例如分析 {@code JdbcTemplate.query(String sql, Object[] args)} 的第 0 个参数时，
 * {@code method} 表示 {@code query} 方法签名，{@code index} 为 0，
 * {@code name} 可以是 {@code sql} 或 {@code param0}，{@code type} 为 {@code java.lang.String}。
 *
 * @param method 参数所属的方法签名
 * @param index  参数下标，从 0 开始
 * @param name   参数名；如果无法从字节码调试信息读取，则使用 {@code paramN}
 * @param type   参数类型；如果签名解析失败，则使用 {@code unknown}
 * @author zhangcs
 */
public record MethodParameterRef(
        MethodSignature method,
        int index,
        String name,
        String type) {

    /**
     * 创建方法形参引用。
     *
     * <p>构造时会补齐空参数名和空类型，避免后续展示或诊断信息出现空值。
     */
    public MethodParameterRef {
        Objects.requireNonNull(method, "method");
        name = name == null || name.isBlank() ? "param" + index : name;
        type = type == null || type.isBlank() ? "unknown" : type;
    }

    /**
     * 使用默认参数名创建形参引用。
     *
     * @param method 参数所属方法
     * @param index  参数下标
     * @return 方法形参引用
     */
    public static MethodParameterRef of(MethodSignature method, int index) {
        return new MethodParameterRef(method, index, "param" + index, parameterType(method, index));
    }

    /**
     * 使用指定参数名创建形参引用。
     *
     * @param method 参数所属方法
     * @param index  参数下标
     * @param name   参数名
     * @return 方法形参引用
     */
    public static MethodParameterRef of(MethodSignature method, int index, String name) {
        return new MethodParameterRef(method, index, name, parameterType(method, index));
    }

    /**
     * 判断参数下标是否在方法签名的参数范围内。
     *
     * @return 参数下标有效时返回 {@code true}
     */
    public boolean isValid() {
        return index >= 0 && index < parameterTypes(method).size();
    }

    /**
     * 返回适合日志和诊断信息展示的参数名称。
     *
     * @return 格式为 {@code class#method[index:name]} 的展示字符串
     */
    public String displayName() {
        return method.getDeclClassType().getFullyQualifiedName()
                + "#" + method.getName() + "[" + index + ":" + name + "]";
    }

    /**
     * 获取指定方法参数下标对应的类型。
     *
     * @param method 方法签名
     * @param index  参数下标
     * @return 参数类型；下标越界时返回 {@code unknown}
     */
    public static String parameterType(MethodSignature method, int index) {
        List<String> types = parameterTypes(method);
        if (index < 0 || index >= types.size()) {
            return "unknown";
        }
        return types.get(index);
    }

    /**
     * 从 SootUp 方法子签名中解析参数类型列表。
     *
     * <p>该方法按逗号分割参数列表，并保留泛型尖括号内部的逗号，避免将
     * {@code Map<String, Object>} 这类类型错误拆分。
     *
     * @param method 方法签名
     * @return 参数类型列表
     */
    public static List<String> parameterTypes(MethodSignature method) {
        String subSignature = method.getSubSignature().toString();
        int open = subSignature.indexOf('(');
        int close = subSignature.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return List.of();
        }

        String raw = subSignature.substring(open + 1, close).trim();
        if (raw.isEmpty()) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int genericDepth = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '<') {
                genericDepth++;
                current.append(c);
            } else if (c == '>') {
                genericDepth--;
                current.append(c);
            } else if (c == ',' && genericDepth == 0) {
                result.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            result.add(current.toString().trim());
        }
        return List.copyOf(result);
    }
}
