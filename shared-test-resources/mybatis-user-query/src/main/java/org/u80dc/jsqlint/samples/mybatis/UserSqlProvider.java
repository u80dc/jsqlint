package org.u80dc.jsqlint.samples.mybatis;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.annotation.ProviderContext;
import org.apache.ibatis.builder.annotation.ProviderMethodResolver;
import org.apache.ibatis.jdbc.SQL;
import java.util.Map;

/** 四类 Provider：默认方法选择、Map/命名参数、上下文、辅助方法与 SQL 构造器。 */
public class UserSqlProvider implements ProviderMethodResolver {
    public static String providerFind(Map<String, Object> parameters, ProviderContext context) {
        var sql = new SQL();
        sql.SELECT("username /* " + context.getMapperMethod().getName() + " */");
        sql.FROM((String) parameters.get("table"));
        sql.WHERE("id=#{id}");
        return sql.toString();
    }

    public static String insert() { return insertSql(); }
    private static String insertSql() { return "INSERT INTO app_users(id,username) VALUES(#{id},#{name})"; }

    public String rename(ProviderContext context, @Param("name") String name) {
        return new SQL().UPDATE("app_users").SET("username=#{name}").WHERE("id=#{id}").toString();
    }

    public static String delete() {
        return new SQL() {{ DELETE_FROM("app_users"); WHERE("id=#{id}"); }}.toString();
    }

    public static String unusedProviderCount() { return "SELECT COUNT(*) FROM app_users"; }
}
