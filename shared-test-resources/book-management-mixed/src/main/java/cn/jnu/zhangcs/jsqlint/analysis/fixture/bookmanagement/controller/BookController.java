package cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.controller;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.dto.BookCreateDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.dto.BookSearchDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.dto.BookUpdateDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.entity.Book;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.service.BookService;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.vo.BookVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/books")
public class BookController {

    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    @PostMapping
    public BookVO create(@RequestBody BookCreateDTO request) {
        return BookVO.from(bookService.create(toBook(request)));
    }

    @GetMapping("/{id}")
    public BookVO get(@PathVariable("id") Long id) {
        return BookVO.from(bookService.findById(id).orElse(null));
    }

    @GetMapping("/search")
    public List<BookVO> search(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "authorId", required = false) Long authorId,
            @RequestParam(value = "minPrice", required = false) BigDecimal minPrice,
            @RequestParam(value = "maxPrice", required = false) BigDecimal maxPrice,
            @RequestParam(value = "ids", required = false) List<Long> ids,
            @RequestParam(value = "keywords", required = false) List<String> keywords,
            @RequestParam(value = "orderBy", required = false) String orderBy,
            @RequestParam(value = "descending", defaultValue = "false") boolean descending) {
        return toVOs(bookService.search(criteria(status, category, authorId, minPrice, maxPrice,
                ids, keywords, orderBy, descending)));
    }

    @GetMapping("/search/native")
    public List<BookVO> searchNative(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "authorId", required = false) Long authorId,
            @RequestParam(value = "minPrice", required = false) BigDecimal minPrice,
            @RequestParam(value = "maxPrice", required = false) BigDecimal maxPrice,
            @RequestParam(value = "ids", required = false) List<Long> ids,
            @RequestParam(value = "keywords", required = false) List<String> keywords,
            @RequestParam(value = "orderBy", required = false) String orderBy,
            @RequestParam(value = "descending", defaultValue = "false") boolean descending) {
        return toVOs(bookService.searchNative(criteria(status, category, authorId, minPrice, maxPrice,
                ids, keywords, orderBy, descending)));
    }

    @PutMapping("/{id}")
    public BookVO update(@PathVariable("id") Long id, @RequestBody BookUpdateDTO request) {
        return BookVO.from(bookService.update(id, toBook(id, request)));
    }

    @PatchMapping("/{id}/status")
    public int updateStatus(@PathVariable("id") Long id, @RequestParam("status") String status) {
        return bookService.updateStatus(id, status);
    }

    @PatchMapping("/{id}/stock")
    public int changeStock(@PathVariable("id") Long id, @RequestParam("delta") int delta) {
        return bookService.changeStock(id, delta);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable("id") Long id) {
        bookService.deleteById(id);
    }

    @DeleteMapping("/by-status")
    public int deleteByStatus(@RequestParam("status") String status) {
        return bookService.deleteByStatus(status);
    }

    private BookSearchDTO criteria(String status, String category, Long authorId,
                                   BigDecimal minPrice, BigDecimal maxPrice, List<Long> ids,
                                   List<String> keywords, String orderBy, boolean descending) {
        BookSearchDTO criteria = new BookSearchDTO();
        criteria.setStatus(status);
        criteria.setCategory(category);
        criteria.setAuthorId(authorId);
        criteria.setMinPrice(minPrice);
        criteria.setMaxPrice(maxPrice);
        criteria.setIds(ids);
        criteria.setKeywords(keywords);
        criteria.setOrderBy(orderBy);
        criteria.setDescending(descending);
        return criteria;
    }

    private Book toBook(BookCreateDTO request) {
        return Book.newBook(request.getIsbn(), request.getTitle(), request.getAuthorId(),
                request.getCategory(), request.getStatus(), request.getPrice(),
                request.getStock(), request.getPublishedDate());
    }

    private Book toBook(Long id, BookUpdateDTO request) {
        Book book = new Book();
        book.setId(id);
        book.setTitle(request.getTitle());
        book.setCategory(request.getCategory());
        book.setStatus(request.getStatus());
        book.setPrice(request.getPrice());
        book.setStock(request.getStock());
        book.setPublishedDate(request.getPublishedDate());
        return book;
    }

    private List<BookVO> toVOs(List<Book> books) {
        return books.stream()
                .map(BookVO::from)
                .collect(Collectors.toList());
    }
}
