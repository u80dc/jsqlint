package cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.service.impl;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.dto.BookSearchDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.entity.Book;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.repository.BookJpaRepository;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.service.BookService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
@SuppressWarnings("unchecked")
public class BookServiceImpl implements BookService {

    private final BookJpaRepository repository;

    private final EntityManager entityManager;

    public BookServiceImpl(BookJpaRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    public Book create(Book book) {
        return repository.save(book);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Book> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Book> search(BookSearchDTO criteria) {
        StringBuilder jpql = new StringBuilder("select b from Book b");
        List<String> predicates = new ArrayList<>();

        if (criteria.getStatus() != null && !criteria.getStatus().isBlank()) {
            predicates.add("b.status = :status");
        }
        if (criteria.getCategory() != null && !criteria.getCategory().isBlank()) {
            predicates.add("b.category = :category");
        }
        if (criteria.getAuthorId() != null) {
            predicates.add("b.authorId = :authorId");
        }
        if (criteria.getMinPrice() != null) {
            predicates.add("b.price >= :minPrice");
        }
        if (criteria.getMaxPrice() != null) {
            predicates.add("b.price <= :maxPrice");
        }
        if (!criteria.getIds().isEmpty()) {
            predicates.add("b.id in :ids");
        }
        if (!criteria.getKeywords().isEmpty()) {
            StringBuilder keywordClause = new StringBuilder("(");
            for (int i = 0; i < criteria.getKeywords().size(); i++) {
                if (i > 0) {
                    keywordClause.append(" or ");
                }
                keywordClause.append("(lower(b.title) like :keyword")
                        .append(i)
                        .append(" or lower(b.isbn) like :keyword")
                        .append(i)
                        .append(')');
            }
            keywordClause.append(')');
            predicates.add(keywordClause.toString());
        }

        appendWhere(jpql, predicates);
        appendOrder(jpql, criteria.getOrderBy(), criteria.isDescending(), "b.");

        TypedQuery<Book> query = entityManager.createQuery(jpql.toString(), Book.class);
        bindSearchParameters(query, criteria);
        return query.getResultList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Book> searchNative(BookSearchDTO criteria) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, isbn, title, author_id, category, status, price, stock, published_date, created_at, updated_at
                FROM books
                """);
        List<Object> parameters = new ArrayList<>();
        List<String> predicates = new ArrayList<>();

        if (criteria.getStatus() != null && !criteria.getStatus().isBlank()) {
            predicates.add("status = ?");
            parameters.add(criteria.getStatus());
        }
        if (criteria.getCategory() != null && !criteria.getCategory().isBlank()) {
            predicates.add("category = ?");
            parameters.add(criteria.getCategory());
        }
        if (criteria.getAuthorId() != null) {
            predicates.add("author_id = ?");
            parameters.add(criteria.getAuthorId());
        }
        if (!criteria.getIds().isEmpty()) {
            StringBuilder inClause = new StringBuilder("id IN (");
            for (int i = 0; i < criteria.getIds().size(); i++) {
                if (i > 0) {
                    inClause.append(", ");
                }
                inClause.append('?');
                parameters.add(criteria.getIds().get(i));
            }
            inClause.append(')');
            predicates.add(inClause.toString());
        }
        if (!criteria.getKeywords().isEmpty()) {
            StringBuilder keywordClause = new StringBuilder("(");
            for (int i = 0; i < criteria.getKeywords().size(); i++) {
                if (i > 0) {
                    keywordClause.append(" OR ");
                }
                keywordClause.append("(LOWER(title) LIKE ? OR LOWER(isbn) LIKE ?)");
                String pattern = "%" + criteria.getKeywords().get(i).toLowerCase() + "%";
                parameters.add(pattern);
                parameters.add(pattern);
            }
            keywordClause.append(')');
            predicates.add(keywordClause.toString());
        }

        appendWhere(sql, predicates);
        appendOrder(sql, criteria.getOrderBy(), criteria.isDescending(), "");

        Query query = entityManager.createNativeQuery(sql.toString(), Book.class);
        for (int i = 0; i < parameters.size(); i++) {
            query.setParameter(i + 1, parameters.get(i));
        }
        return query.getResultList();
    }

    @Override
    public Book update(Long id, Book book) {
        StringBuilder jpql = new StringBuilder("update Book b set ");
        List<String> assignments = new ArrayList<>();

        if (book.getTitle() != null && !book.getTitle().isBlank()) {
            assignments.add("b.title = :title");
        }
        if (book.getCategory() != null && !book.getCategory().isBlank()) {
            assignments.add("b.category = :category");
        }
        if (book.getStatus() != null && !book.getStatus().isBlank()) {
            assignments.add("b.status = :status");
        }
        if (book.getPrice() != null) {
            assignments.add("b.price = :price");
        }
        if (book.getStock() != null) {
            assignments.add("b.stock = :stock");
        }
        if (book.getPublishedDate() != null) {
            assignments.add("b.publishedDate = :publishedDate");
        }
        assignments.add("b.updatedAt = :updatedAt");

        for (int i = 0; i < assignments.size(); i++) {
            if (i > 0) {
                jpql.append(", ");
            }
            jpql.append(assignments.get(i));
        }
        jpql.append(" where b.id = :id");

        Query query = entityManager.createQuery(jpql.toString());
        query.setParameter("id", id);
        query.setParameter("updatedAt", LocalDateTime.now());
        bindUpdateParameters(query, book);
        query.executeUpdate();
        return repository.findById(id).orElse(book);
    }

    @Override
    public int updateStatus(Long id, String status) {
        return repository.updateStatus(id, status);
    }

    @Override
    public int changeStock(Long id, int delta) {
        return repository.changeStockNative(id, delta);
    }

    @Override
    public void deleteById(Long id) {
        repository.deleteById(id);
    }

    @Override
    public int deleteByStatus(String status) {
        return repository.deleteNativeByStatus(status);
    }

    private void appendWhere(StringBuilder sql, List<String> predicates) {
        if (predicates.isEmpty()) {
            return;
        }
        sql.append(" WHERE ");
        for (int i = 0; i < predicates.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            sql.append(predicates.get(i));
        }
    }

    private void appendOrder(StringBuilder sql, String orderBy, boolean descending, String prefix) {
        String column = switch (orderBy == null ? "" : orderBy) {
            case "title" -> prefix + "title";
            case "price" -> prefix + "price";
            case "stock" -> prefix + "stock";
            case "publishedDate" -> prefix.isEmpty() ? "published_date" : prefix + "publishedDate";
            default -> prefix + "id";
        };
        sql.append(" ORDER BY ").append(column);
        sql.append(descending ? " DESC" : " ASC");
    }

    private void bindSearchParameters(Query query, BookSearchDTO criteria) {
        if (criteria.getStatus() != null && !criteria.getStatus().isBlank()) {
            query.setParameter("status", criteria.getStatus());
        }
        if (criteria.getCategory() != null && !criteria.getCategory().isBlank()) {
            query.setParameter("category", criteria.getCategory());
        }
        if (criteria.getAuthorId() != null) {
            query.setParameter("authorId", criteria.getAuthorId());
        }
        if (criteria.getMinPrice() != null) {
            query.setParameter("minPrice", criteria.getMinPrice());
        }
        if (criteria.getMaxPrice() != null) {
            query.setParameter("maxPrice", criteria.getMaxPrice());
        }
        if (!criteria.getIds().isEmpty()) {
            query.setParameter("ids", criteria.getIds());
        }
        for (int i = 0; i < criteria.getKeywords().size(); i++) {
            query.setParameter("keyword" + i, "%" + criteria.getKeywords().get(i).toLowerCase() + "%");
        }
    }

    private void bindUpdateParameters(Query query, Book book) {
        if (book.getTitle() != null && !book.getTitle().isBlank()) {
            query.setParameter("title", book.getTitle());
        }
        if (book.getCategory() != null && !book.getCategory().isBlank()) {
            query.setParameter("category", book.getCategory());
        }
        if (book.getStatus() != null && !book.getStatus().isBlank()) {
            query.setParameter("status", book.getStatus());
        }
        if (book.getPrice() != null) {
            query.setParameter("price", book.getPrice());
        }
        if (book.getStock() != null) {
            query.setParameter("stock", book.getStock());
        }
        if (book.getPublishedDate() != null) {
            query.setParameter("publishedDate", book.getPublishedDate());
        }
    }
}
