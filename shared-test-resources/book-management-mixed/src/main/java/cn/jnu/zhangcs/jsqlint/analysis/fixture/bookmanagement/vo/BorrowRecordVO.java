package cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.vo;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.entity.BorrowRecord;

import java.time.LocalDateTime;

public class BorrowRecordVO {

    private Long id;
    private Long bookId;
    private String borrowerName;
    private String borrowerPhone;
    private String status;
    private LocalDateTime borrowedAt;
    private LocalDateTime dueAt;
    private LocalDateTime returnedAt;

    public static BorrowRecordVO from(BorrowRecord record) {
        if (record == null) {
            return null;
        }
        BorrowRecordVO vo = new BorrowRecordVO();
        vo.id = record.getId();
        vo.bookId = record.getBookId();
        vo.borrowerName = record.getBorrowerName();
        vo.borrowerPhone = record.getBorrowerPhone();
        vo.status = record.getStatus();
        vo.borrowedAt = record.getBorrowedAt();
        vo.dueAt = record.getDueAt();
        vo.returnedAt = record.getReturnedAt();
        return vo;
    }

    public Long getId() {
        return id;
    }

    public Long getBookId() {
        return bookId;
    }

    public String getBorrowerName() {
        return borrowerName;
    }

    public String getBorrowerPhone() {
        return borrowerPhone;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getBorrowedAt() {
        return borrowedAt;
    }

    public LocalDateTime getDueAt() {
        return dueAt;
    }

    public LocalDateTime getReturnedAt() {
        return returnedAt;
    }
}
