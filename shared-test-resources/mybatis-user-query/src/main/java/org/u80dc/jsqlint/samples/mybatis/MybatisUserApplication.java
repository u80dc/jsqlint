package org.u80dc.jsqlint.samples.mybatis;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** 可以独立运行的 MyBatis/H2 项目；分析测试只编译，不运行本入口。 */
public final class MybatisUserApplication {
    public static void main(String[] args) throws Exception {
        var configuration = new Configuration();
        configuration.setEnvironment(new Environment("sample", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:mybatis_users;DB_CLOSE_DELAY=-1", "sa", "")));
        String resource = "mappers/UserMapper.xml";
        try (var input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        var factory = new SqlSessionFactoryBuilder().build(configuration);
        try (var session = factory.openSession(true)) {
            var mapper = session.getMapper(UserMapper.class);
            mapper.createTable();
            new UserService(mapper).exercise();
        }
    }
}
