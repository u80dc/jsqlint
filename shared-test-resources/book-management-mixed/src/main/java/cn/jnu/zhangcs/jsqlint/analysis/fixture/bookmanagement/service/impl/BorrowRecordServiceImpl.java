package cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.service.impl;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.dto.BorrowSearchDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.entity.BorrowRecord;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.repository.BorrowRecordJdbcRepository;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.service.BookService;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.service.BorrowRecordService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class BorrowRecordServiceImpl implements BorrowRecordService {

    private final BorrowRecordJdbcRepository repository;

    private final BookService bookService;

    public BorrowRecordServiceImpl(BorrowRecordJdbcRepository repository, BookService bookService) {
        this.repository = repository;
        this.bookService = bookService;
    }

    @Override
    public BorrowRecord borrow(BorrowRecord record) {
        record.setStatus("BORROWED");
        BorrowRecord saved = repository.create(record);
        bookService.changeStock(record.getBookId(), -1);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BorrowRecord> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BorrowRecord> search(BorrowSearchDTO criteria) {
        return repository.search(criteria);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BorrowRecord> searchWithNamedParameters(BorrowSearchDTO criteria) {
        return repository.searchWithNamedParameters(criteria);
    }

    @Override
    public int returnBook(Long id, LocalDateTime returnedAt, String status) {
        BorrowRecord record = repository.findById(id).orElse(null);
        int affectedRows = repository.markReturned(id,
                returnedAt == null ? LocalDateTime.now() : returnedAt,
                status == null || status.isBlank() ? "RETURNED" : status);
        if (record != null) {
            bookService.changeStock(record.getBookId(), 1);
        }
        return affectedRows;
    }

    @Override
    public int deleteByStatuses(Collection<String> statuses, boolean includeReturned) {
        return repository.deleteByStatuses(statuses, includeReturned);
    }

    @Override
    @Transactional(readOnly = true)
    public int countByStatusUsingLiteral(String status) {
        return repository.countByStatusUsingLiteral(status);
    }
}
