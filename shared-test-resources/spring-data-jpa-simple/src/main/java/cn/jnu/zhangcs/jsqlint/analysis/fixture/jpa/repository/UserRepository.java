package cn.jnu.zhangcs.jsqlint.analysis.fixture.jpa.repository;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.jpa.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 数据访问层
 *
 * @author zhangcs
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    List<User> findByStatusOrderByCreatedAtDesc(String status);

    List<User> findByStatusAndAgeGreaterThanEqual(String status, Integer minAge);

    Optional<User> findFirstByEmail(String email);

    long countByStatus(String status);

    boolean existsByUsername(String username);

    @Transactional
    void deleteByEmail(String email);

    @Query("select u from User u where u.username = :username")
    Optional<User> findByUsernameQuery(@Param("username") String username);

    @Query("select u from User u where u.status = :status order by u.id asc")
    List<User> findByStatus(@Param("status") String status);

    @Query(value = "SELECT id, username, email, status, age, created_at, updated_at FROM users WHERE status = ?1",
            nativeQuery = true)
    List<User> findNativeByStatus(String status);

    @Transactional
    @Modifying
    @Query("update User u set u.status = :status where u.id = :id")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Transactional
    @Modifying
    @Query(value = "UPDATE users SET email = ?2, updated_at = CURRENT_TIMESTAMP WHERE id = ?1",
            nativeQuery = true)
    int updateEmailNative(Long id, String email);

    @Transactional
    @Modifying
    @NativeQuery("DELETE FROM users WHERE status = ?1")
    int deleteNativeByStatus(String status);

    List<User> findXmlByStatus(@Param("status") String status);

    long countXmlByStatus(@Param("status") String status);

    List<User> findNativeXmlByEmailDomain(String domain);
}
