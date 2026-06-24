package cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.controller;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.dto.AuthorCreateDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.dto.AuthorSearchDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.dto.AuthorUpdateDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.entity.Author;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.service.AuthorService;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.vo.AuthorVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/authors")
public class AuthorController {

    private final AuthorService authorService;

    public AuthorController(AuthorService authorService) {
        this.authorService = authorService;
    }

    @PostMapping
    public AuthorVO create(@RequestBody AuthorCreateDTO request) {
        return AuthorVO.from(authorService.create(toAuthor(request)));
    }

    @GetMapping("/{id}")
    public AuthorVO get(@PathVariable("id") Long id) {
        return AuthorVO.from(authorService.findById(id));
    }

    @GetMapping("/search/provider")
    public List<AuthorVO> searchUsingProvider(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "country", required = false) String country,
            @RequestParam(value = "ids", required = false) List<Long> ids,
            @RequestParam(value = "keywords", required = false) List<String> keywords,
            @RequestParam(value = "orderBy", required = false) String orderBy,
            @RequestParam(value = "descending", defaultValue = "false") boolean descending) {
        return toVOs(authorService.searchUsingProvider(criteria(status, country, ids, keywords, orderBy, descending)));
    }

    @GetMapping("/search/xml")
    public List<AuthorVO> searchUsingXml(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "country", required = false) String country,
            @RequestParam(value = "ids", required = false) List<Long> ids,
            @RequestParam(value = "keywords", required = false) List<String> keywords,
            @RequestParam(value = "orderBy", required = false) String orderBy,
            @RequestParam(value = "descending", defaultValue = "false") boolean descending) {
        return toVOs(authorService.searchUsingXml(criteria(status, country, ids, keywords, orderBy, descending)));
    }

    @GetMapping("/count")
    public long count(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "country", required = false) String country) {
        return authorService.countUsingXml(criteria(status, country, null, null, null, false));
    }

    @PutMapping("/{id}")
    public int update(@PathVariable("id") Long id, @RequestBody AuthorUpdateDTO request) {
        Author author = new Author();
        author.setId(id);
        author.setName(request.getName());
        author.setCountry(request.getCountry());
        author.setStatus(request.getStatus());
        return authorService.update(author);
    }

    @DeleteMapping("/{id}")
    public int delete(@PathVariable("id") Long id) {
        return authorService.deleteById(id);
    }

    @DeleteMapping("/by-statuses")
    public int deleteByStatuses(@RequestBody List<String> statuses) {
        return authorService.deleteByStatuses(statuses);
    }

    private AuthorSearchDTO criteria(String status, String country, List<Long> ids,
                                     List<String> keywords, String orderBy, boolean descending) {
        AuthorSearchDTO criteria = new AuthorSearchDTO();
        criteria.setStatus(status);
        criteria.setCountry(country);
        criteria.setIds(ids);
        criteria.setKeywords(keywords);
        criteria.setOrderBy(orderBy);
        criteria.setDescending(descending);
        return criteria;
    }

    private Author toAuthor(AuthorCreateDTO request) {
        Author author = new Author();
        author.setName(request.getName());
        author.setCountry(request.getCountry());
        author.setStatus(request.getStatus());
        return author;
    }

    private List<AuthorVO> toVOs(List<Author> authors) {
        return authors.stream()
                .map(AuthorVO::from)
                .collect(Collectors.toList());
    }
}
