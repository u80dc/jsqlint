package cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.mapper;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.dto.AuthorSearchDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.entity.Author;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.UpdateProvider;

import java.util.List;

@Mapper
public interface AuthorMapper {

    @Insert("""
            INSERT INTO authors (name, country, status)
            VALUES (#{name}, #{country}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(Author author);

    @Select("""
            SELECT id, name, country, status, created_at, updated_at
            FROM authors
            WHERE id = #{id}
            """)
    Author findById(@Param("id") Long id);

    @Select("""
            SELECT id, name, country, status, created_at, updated_at
            FROM authors
            WHERE name = #{name}
            """)
    Author findByName(@Param("name") String name);

    @Update("""
            UPDATE authors
            SET country = #{country},
                status = #{status},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int update(Author author);

    @Delete("DELETE FROM authors WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    @SelectProvider(type = AuthorSqlProvider.class, method = "search")
    List<Author> searchUsingProvider(AuthorSearchDTO criteria);

    @UpdateProvider(type = AuthorSqlProvider.class, method = "updateDynamic")
    int updateUsingProvider(Author author);

    @DeleteProvider(type = AuthorSqlProvider.class, method = "deleteByStatuses")
    int deleteByStatusesUsingProvider(@Param("statuses") List<String> statuses);

    List<Author> searchUsingXml(AuthorSearchDTO criteria);

    long countUsingXml(AuthorSearchDTO criteria);
}
