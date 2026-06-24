package cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.dto;

import java.util.ArrayList;
import java.util.List;

public class BorrowSearchDTO {

    private Long bookId;

    private String status;

    private String borrowerName;

    private List<Long> ids = new ArrayList<>();

    private String orderBy;

    private boolean descending;

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getBorrowerName() {
        return borrowerName;
    }

    public void setBorrowerName(String borrowerName) {
        this.borrowerName = borrowerName;
    }

    public List<Long> getIds() {
        return ids;
    }

    public void setIds(List<Long> ids) {
        this.ids = ids == null ? new ArrayList<>() : ids;
    }

    public String getOrderBy() {
        return orderBy;
    }

    public void setOrderBy(String orderBy) {
        this.orderBy = orderBy;
    }

    public boolean isDescending() {
        return descending;
    }

    public void setDescending(boolean descending) {
        this.descending = descending;
    }
}
