package cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.repository;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.entity.Book;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookJpaRepository extends JpaRepository<Book, Long> {

    @Query("select b from Book b where b.isbn = :isbn")
    Optional<Book> findByIsbn(@Param("isbn") String isbn);

    @Query("select b from Book b where b.authorId = :authorId order by b.id asc")
    List<Book> findByAuthorId(@Param("authorId") Long authorId);

    @Query(value = """
            SELECT id, isbn, title, author_id, category, status, price, stock, published_date, created_at, updated_at
            FROM books
            WHERE category = ?1 AND status = ?2
            """, nativeQuery = true)
    List<Book> findNativeByCategoryAndStatus(String category, String status);

    @Transactional
    @Modifying
    @Query("update Book b set b.status = :status, b.updatedAt = current_timestamp where b.id = :id")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Transactional
    @Modifying
    @Query(value = "UPDATE books SET stock = stock + ?2, updated_at = CURRENT_TIMESTAMP WHERE id = ?1",
            nativeQuery = true)
    int changeStockNative(Long id, int delta);

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM books WHERE status = ?1", nativeQuery = true)
    int deleteNativeByStatus(String status);
}
