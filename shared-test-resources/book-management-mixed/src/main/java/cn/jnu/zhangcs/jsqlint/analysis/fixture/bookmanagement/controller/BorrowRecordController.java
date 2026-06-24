package cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.controller;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.dto.BorrowCreateDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.dto.BorrowSearchDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.dto.ReturnBookDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.entity.BorrowRecord;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.service.BorrowRecordService;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.vo.BorrowRecordVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/borrows")
public class BorrowRecordController {

    private final BorrowRecordService borrowRecordService;

    public BorrowRecordController(BorrowRecordService borrowRecordService) {
        this.borrowRecordService = borrowRecordService;
    }

    @PostMapping
    public BorrowRecordVO borrow(@RequestBody BorrowCreateDTO request) {
        return BorrowRecordVO.from(borrowRecordService.borrow(toRecord(request)));
    }

    @GetMapping("/{id}")
    public BorrowRecordVO get(@PathVariable("id") Long id) {
        return BorrowRecordVO.from(borrowRecordService.findById(id).orElse(null));
    }

    @GetMapping("/search")
    public List<BorrowRecordVO> search(
            @RequestParam(value = "bookId", required = false) Long bookId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "borrowerName", required = false) String borrowerName,
            @RequestParam(value = "ids", required = false) List<Long> ids,
            @RequestParam(value = "orderBy", required = false) String orderBy,
            @RequestParam(value = "descending", defaultValue = "false") boolean descending) {
        return toVOs(borrowRecordService.search(criteria(bookId, status, borrowerName, ids, orderBy, descending)));
    }

    @GetMapping("/search/named")
    public List<BorrowRecordVO> searchWithNamedParameters(
            @RequestParam(value = "bookId", required = false) Long bookId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "borrowerName", required = false) String borrowerName,
            @RequestParam(value = "ids", required = false) List<Long> ids,
            @RequestParam(value = "orderBy", required = false) String orderBy,
            @RequestParam(value = "descending", defaultValue = "false") boolean descending) {
        return toVOs(borrowRecordService.searchWithNamedParameters(
                criteria(bookId, status, borrowerName, ids, orderBy, descending)));
    }

    @PatchMapping("/{id}/return")
    public int returnBook(@PathVariable("id") Long id, @RequestBody ReturnBookDTO request) {
        return borrowRecordService.returnBook(id, request.getReturnedAt(), request.getStatus());
    }

    @GetMapping("/count")
    public int countByStatus(@RequestParam(value = "status", required = false) String status) {
        return borrowRecordService.countByStatusUsingLiteral(status);
    }

    @DeleteMapping("/by-statuses")
    public int deleteByStatuses(
            @RequestBody List<String> statuses,
            @RequestParam(value = "includeReturned", defaultValue = "false") boolean includeReturned) {
        return borrowRecordService.deleteByStatuses(statuses, includeReturned);
    }

    private BorrowSearchDTO criteria(Long bookId, String status, String borrowerName,
                                     List<Long> ids, String orderBy, boolean descending) {
        BorrowSearchDTO criteria = new BorrowSearchDTO();
        criteria.setBookId(bookId);
        criteria.setStatus(status);
        criteria.setBorrowerName(borrowerName);
        criteria.setIds(ids);
        criteria.setOrderBy(orderBy);
        criteria.setDescending(descending);
        return criteria;
    }

    private BorrowRecord toRecord(BorrowCreateDTO request) {
        BorrowRecord record = new BorrowRecord();
        record.setBookId(request.getBookId());
        record.setBorrowerName(request.getBorrowerName());
        record.setBorrowerPhone(request.getBorrowerPhone());
        record.setDueAt(request.getDueAt());
        return record;
    }

    private List<BorrowRecordVO> toVOs(List<BorrowRecord> records) {
        return records.stream()
                .map(BorrowRecordVO::from)
                .collect(Collectors.toList());
    }
}
