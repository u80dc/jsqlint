package cn.jnu.zhangcs.jsqlint.analysis.parameter.infer;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * 默认外部输入样例值生成器。
 *
 * <p>该实现按 Java 类型生成随机内容。常见基础类型会生成对应 Java 值；
 * 自定义对象类型会生成一个带类型名和随机标识的 {@link Map}，避免在没有构造器信息时
 * 强行反射创建对象。
 *
 * @author zhangcs
 */
public final class RandomExternalValueGenerator implements ExternalValueGenerator {

    private final Random random;

    public RandomExternalValueGenerator() {
        this(new Random());
    }

    public RandomExternalValueGenerator(long seed) {
        this(new Random(seed));
    }

    public RandomExternalValueGenerator(Random random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public Object generate(String type, String name) {
        String normalizedType = normalizeType(type);
        return switch (normalizedType) {
            case "java.lang.String", "String" -> randomString(name);
            case "boolean", "java.lang.Boolean" -> random.nextBoolean();
            case "byte", "java.lang.Byte" -> (byte) random.nextInt(Byte.MAX_VALUE + 1);
            case "short", "java.lang.Short" -> (short) random.nextInt(Short.MAX_VALUE + 1);
            case "int", "java.lang.Integer" -> random.nextInt(1, 10_000);
            case "long", "java.lang.Long" -> random.nextLong(1L, 1_000_000L);
            case "float", "java.lang.Float" -> random.nextFloat() * 1_000.0F;
            case "double", "java.lang.Double" -> random.nextDouble() * 1_000.0D;
            case "char", "java.lang.Character" -> (char) ('a' + random.nextInt(26));
            case "java.math.BigDecimal", "BigDecimal" -> BigDecimal.valueOf(random.nextLong(1L, 10_000L), 2);
            default -> generateReferenceValue(normalizedType, name);
        };
    }

    private String randomString(String name) {
        String prefix = name == null || name.isBlank() ? "value" : name;
        return prefix + "_" + Integer.toUnsignedString(random.nextInt(), 36);
    }

    private Object generateReferenceValue(String type, String name) {
        if (type.startsWith("[") || type.endsWith("[]")) {
            return new Object[]{randomString(name)};
        }
        if (type.startsWith("java.util.List") || type.startsWith("java.util.Collection")
                || type.startsWith("java.util.Set")) {
            return java.util.List.of(randomString(name));
        }
        if (type.startsWith("java.util.Map")) {
            return Map.of("value", randomString(name));
        }

        Map<String, Object> object = new LinkedHashMap<>();
        object.put("__type", type == null || type.isBlank() ? "java.lang.Object" : type);
        object.put("__id", randomString(name));
        return object;
    }

    private String normalizeType(String type) {
        return type == null || type.isBlank() ? "java.lang.Object" : type.trim();
    }
}
