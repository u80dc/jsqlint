package org.u80dc.jsqlint.core.parameter;

import org.u80dc.jsqlint.core.node.SqlValue;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.*;

/** 只生成有明确 Java 类型的样例；不使用 Map 冒充目标对象，不加载目标项目类。 @author u80dc */
public final class RandomExternalValueGenerator implements ExternalValueGenerator {
    private final Random random;
    public RandomExternalValueGenerator() { this.random = new Random(); }
    public RandomExternalValueGenerator(long seed) { this.random = new Random(seed); }

    @Override
    public SqlValue generate(String type, String name) {
        Object value = switch (type) {
            case "java.lang.String" -> name + "_" + Long.toUnsignedString(random.nextLong(), 36);
            case "boolean", "java.lang.Boolean" -> random.nextBoolean();
            case "byte", "java.lang.Byte" -> (byte) random.nextInt(128);
            case "short", "java.lang.Short" -> (short) random.nextInt(1000);
            case "int", "java.lang.Integer" -> random.nextInt(1, 10000);
            case "long", "java.lang.Long" -> random.nextLong(1, 1000000);
            case "float", "java.lang.Float" -> random.nextFloat() * 1000;
            case "double", "java.lang.Double" -> random.nextDouble() * 1000;
            case "char", "java.lang.Character" -> (char) ('a' + random.nextInt(26));
            case "java.math.BigDecimal" -> BigDecimal.valueOf(random.nextLong(1, 100000), 2);
            case "java.math.BigInteger" -> BigInteger.valueOf(random.nextLong(1, 1000000));
            case "java.util.UUID" -> new UUID(random.nextLong(), random.nextLong());
            case "java.time.LocalDate" -> LocalDate.of(2020, 1, 1).plusDays(random.nextInt(3650));
            case "java.time.LocalTime" -> LocalTime.ofSecondOfDay(random.nextInt(86400));
            case "java.time.LocalDateTime" -> LocalDateTime.of(2020, 1, 1, 0, 0).plusSeconds(random.nextInt(31536000));
            case "java.time.Instant" -> Instant.ofEpochSecond(1577836800L + random.nextInt(31536000));
            default -> null;
        };
        return value == null ? new SqlValue.Unknown("No external sample generator for " + type) : new SqlValue.Known(value);
    }
}
