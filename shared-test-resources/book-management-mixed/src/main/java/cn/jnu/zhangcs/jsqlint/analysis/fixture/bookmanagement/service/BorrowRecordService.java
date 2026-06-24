package cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.service;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.dto.BorrowSearchDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.entity.BorrowRecord;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BorrowRecordService {

    BorrowRecord borrow(BorrowRecord record);

    Optional<BorrowRecord> findById(Long id);

    List<BorrowRecord> search(BorrowSearchDTO criteria);

    List<BorrowRecord> searchWithNamedParameters(BorrowSearchDTO criteria);

    int returnBook(Long id, LocalDateTime returnedAt, String status);

    int deleteByStatuses(Collection<String> statuses, boolean includeReturned);

    int countByStatusUsingLiteral(String status);
}
