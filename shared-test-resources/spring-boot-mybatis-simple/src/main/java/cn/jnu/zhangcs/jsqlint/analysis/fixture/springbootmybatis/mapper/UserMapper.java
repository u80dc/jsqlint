package cn.jnu.zhangcs.jsqlint.analysis.fixture.springbootmybatis.mapper;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.springbootmybatis.model.dto.UserSearchDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.springbootmybatis.entity.User;
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

/**
 * 数据访问层
 *
 * @author zhangcs
 */
@Mapper
public interface UserMapper {

    @Insert("""
            INSERT INTO users (username, email, status, age)
            VALUES (#{username}, #{email}, #{status}, #{age})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(User user);

    @Select("""
            SELECT id, username, email, status, age, created_at, updated_at
            FROM users
            WHERE id = #{id}
            """)
    User findById(@Param("id") Long id);

    @Select("""
            SELECT id, username, email, status, age, created_at, updated_at
            FROM users
            WHERE username = #{username}
            """)
    User findByUsername(@Param("username") String username);

    @Update("""
            UPDATE users
            SET status = #{status},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Delete("DELETE FROM users WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    @SelectProvider(type = UserSqlProvider.class, method = "search")
    List<User> searchUsingProvider(UserSearchDTO criteria);

    @UpdateProvider(type = UserSqlProvider.class, method = "updateDynamic")
    int updateUsingProvider(User user);

    @DeleteProvider(type = UserSqlProvider.class, method = "deleteByStatuses")
    int deleteByStatusesUsingProvider(@Param("statuses") List<String> statuses);

    List<User> searchUsingXml(UserSearchDTO criteria);

    int updateUsingXml(User user);

    long countUsingXml(UserSearchDTO criteria);

    int resetData();
}
