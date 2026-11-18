package org.u80dc.jsqlint.samples.mybatismatrix;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import java.util.List;
import java.util.Properties;

/** 显式传入 databaseId/variables；支持用相同驱动运行 H2 变体和通用回退定义。 */
public final class MybatisUsageApplication {
    public static void main(String[] args) throws Exception {
        run("h2");
        run(null);
        System.out.println("MyBatis usage matrix passed (h2 + default)");
    }

    public static void run(String databaseId) throws Exception {
        var configuration = new Configuration();
        configuration.setDatabaseId(databaseId);
        var variables = new Properties(); variables.setProperty("sampleTable", "mybatis_usage");
        configuration.setVariables(variables);
        configuration.setEnvironment(new Environment("sample", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:mybatis_usage", "sa", "")));
        // 故意先读取引用方，验证后读取的跨文件/嵌套片段能补齐待解析定义。
        for (String resource : List.of("mappers/UsageMapper.xml", "mappers/SharedColumns.xml")) {
            try (var input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        var factory = new SqlSessionFactoryBuilder().build(configuration);
        try (var session = factory.openSession(true)) {
            exercise(session.getMapper(UsageMapper.class));
        }
    }

    public static void exercise(UsageMapper mapper) {
        mapper.createTable();
        check(mapper.insertXml(1L, "O'Neil", null) == 1);
        check(mapper.insertXml(2L, "Bob", "bob@example.test") == 1);
        check(mapper.chooseSearch(1L, "ignored").equals(List.of("O'Neil")));
        check(mapper.chooseSearch(0L, "Bob").equals(List.of("Bob")));
        check(mapper.chooseSearch(0L, null).equals(List.of("O'Neil", "Bob")));
        check(mapper.trimUpdate(1L, "Alice", "alice@example.test") == 1);
        check(mapper.trimUpdate(2L, "Bobby", null) == 1);
        check(mapper.implicitNames(1L, "Bobby").equals(List.of("Alice", "Bobby")));
        check("Alice".equals(mapper.scalarFallback(1L)));
        check(mapper.repeated(2L).equals(List.of("Bobby")));
        check(((Number) mapper.included(1L).get("ID")).longValue() == 1L);
        check("Alice".equals(mapper.databaseVariant(1L)));
        check(mapper.deleteXml(2L) == 1);
    }

    private static void check(boolean condition) {
        if (!condition) throw new IllegalStateException("MyBatis scenario assertion failed");
    }
}
