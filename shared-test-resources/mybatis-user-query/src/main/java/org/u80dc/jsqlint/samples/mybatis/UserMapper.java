package org.u80dc.jsqlint.samples.mybatis;

import org.apache.ibatis.annotations.*;
import java.util.List;

/** 注解与 XML 分工定义 SQL，同一个方法只使用一种来源。 */
public interface UserMapper {
    @Update("CREATE TABLE IF NOT EXISTS app_users(id BIGINT PRIMARY KEY, username VARCHAR(100))")
    void createTable();

    @Insert("INSERT INTO app_users(id,username) VALUES(#{id},#{name})")
    int insert(@Param("id") long id, @Param("name") String name);

    String find(@Param("id") long id);
    int rename(@Param("id") long id, @Param("name") String name);
    List<String> search(@Param("column") String column, @Param("value") String value);
    List<String> optional(@Param("name") String name);

    @Delete("DELETE FROM app_users WHERE id=#{id}")
    int delete(@Param("id") long id);

    @Select("SELECT COUNT(*) FROM app_users")
    long unusedCount();
}
