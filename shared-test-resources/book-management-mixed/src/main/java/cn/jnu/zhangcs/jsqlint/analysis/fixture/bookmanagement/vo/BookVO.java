package cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.vo;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.entity.Book;

import java.math.BigDecimal;
import java.time.LocalDate;

public class BookVO {

    private Long id;
    private String isbn;
    private String title;
    private Long authorId;
    private String category;
    private String status;
    private BigDecimal price;
    private Integer stock;
    private LocalDate publishedDate;

    public static BookVO from(Book book) {
        if (book == null) {
            return null;
        }
        BookVO vo = new BookVO();
        vo.id = book.getId();
        vo.isbn = book.getIsbn();
        vo.title = book.getTitle();
        vo.authorId = book.getAuthorId();
        vo.category = book.getCategory();
        vo.status = book.getStatus();
        vo.price = book.getPrice();
        vo.stock = book.getStock();
        vo.publishedDate = book.getPublishedDate();
        return vo;
    }

    public Long getId() {
        return id;
    }

    public String getIsbn() {
        return isbn;
    }

    public String getTitle() {
        return title;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public String getCategory() {
        return category;
    }

    public String getStatus() {
        return status;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Integer getStock() {
        return stock;
    }

    public LocalDate getPublishedDate() {
        return publishedDate;
    }
}
