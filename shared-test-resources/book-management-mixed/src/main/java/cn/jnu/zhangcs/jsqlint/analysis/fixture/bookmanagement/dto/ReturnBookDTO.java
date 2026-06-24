package cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.dto;

import java.time.LocalDateTime;

public class ReturnBookDTO {

    private LocalDateTime returnedAt;

    private String status;

    public LocalDateTime getReturnedAt() {
        return returnedAt;
    }

    public void setReturnedAt(LocalDateTime returnedAt) {
        this.returnedAt = returnedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
