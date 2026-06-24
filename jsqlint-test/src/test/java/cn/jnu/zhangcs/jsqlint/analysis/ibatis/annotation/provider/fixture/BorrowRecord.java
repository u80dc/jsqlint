package cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture;

import java.time.LocalDateTime;

public class BorrowRecord {

    private LocalDateTime returnDate;
    private Integer status;

    public LocalDateTime getReturnDate() {
        return returnDate;
    }

    public Integer getStatus() {
        return status;
    }
}
