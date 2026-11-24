package org.u80dc.jsqlint.core;

import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.node.SqlValueSnapshots;
import org.u80dc.jsqlint.core.parameter.InferredParameterValue;

import java.util.*;

/** 模板求值环境的名称布局；与输入来源、推断候选和最终渲染模型分开保存。 */
public record TemplateInputLayout(Map<String, String> aliases, Optional<String> fallback,
                                  Optional<String> parameterMapName, Map<String, Object> constants,
                                  Map<String, String> renderAliases) {
    public TemplateInputLayout {
        aliases = Collections.unmodifiableMap(new LinkedHashMap<>(aliases));
        Objects.requireNonNull(fallback); Objects.requireNonNull(parameterMapName);
        constants = SqlValueSnapshots.parameters(constants);
        renderAliases = Collections.unmodifiableMap(new LinkedHashMap<>(renderAliases));
        for (String name : renderAliases.keySet())
            if (aliases.containsKey(name) || parameterMapName.filter(name::equals).isPresent())
                throw new IllegalArgumentException("Template render alias conflicts with a business or reserved name: " + name);
    }
    @Override public Map<String, Object> constants() { return SqlValueSnapshots.parameters(constants); }
    public static TemplateInputLayout empty() {
        return new TemplateInputLayout(Map.of(), Optional.empty(), Optional.empty(), Map.of(), Map.of());
    }
    /** 一个输入在业务别名、渲染别名和回退名称中共享同一个本次活动值。 */
    public Map<String, Object> environment(Map<String, InferredParameterValue> model) {
        var values = new LinkedHashMap<String, Object>();
        var activity = new HashMap<String, Object>();
        aliases.forEach((alias, key) -> values.put(alias, value(model, key, activity)));
        parameterMapName.ifPresent(name -> values.put(name, Collections.unmodifiableMap(new LinkedHashMap<>(values))));
        renderAliases.forEach((alias, key) -> values.put(alias, value(model, key, activity)));
        // 已恢复的结构常量优先，渲染别名继续保存其Java来源；两者均不进入业务参数Map。
        values.putAll(constants());
        if (fallback.isEmpty()) return Collections.unmodifiableMap(values);
        Object single = value(model, fallback.get(), activity);
        return Collections.unmodifiableMap(new AbstractMap<>() {
            @Override public Set<Entry<String, Object>> entrySet() { return values.entrySet(); }
            @Override public boolean containsKey(Object key) { return values.containsKey(key) || identifier(key); }
            @Override public Object get(Object key) { return values.containsKey(key) ? values.get(key) : identifier(key) ? single : null; }
        });
    }
    private static boolean identifier(Object key) {
        return key instanceof String name && name.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*");
    }
    private static Object value(Map<String, InferredParameterValue> model, String key, Map<String, Object> activity) {
        if (activity.containsKey(key)) return activity.get(key);
        var inferred = model.get(key);
        Object value = inferred == null ? new SqlValue.Unknown("Template parameter inference was not requested: " + key)
                : inferred.value() instanceof SqlValue.Known known ? known.value() : inferred.value();
        activity.put(key, value);
        return value;
    }
}
