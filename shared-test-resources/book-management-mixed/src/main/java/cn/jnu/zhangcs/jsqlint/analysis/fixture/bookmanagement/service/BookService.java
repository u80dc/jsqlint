package cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.service;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.dto.BookSearchDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.entity.Book;

import java.util.List;
import java.util.Optional;

public interface BookService {

    Book create(Book book);

    Optional<Book> findById(Long id);

    List<Book> search(BookSearchDTO criteria);

    List<Book> searchNative(BookSearchDTO criteria);

    Book update(Long id, Book book);

    int updateStatus(Long id, String status);

    int changeStock(Long id, int delta);

    void deleteById(Long id);

    int deleteByStatus(String status);
}
