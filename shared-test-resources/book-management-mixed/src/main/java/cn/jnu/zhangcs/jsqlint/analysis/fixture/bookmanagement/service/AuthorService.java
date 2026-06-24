package cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.service;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.dto.AuthorSearchDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.entity.Author;

import java.util.List;

public interface AuthorService {

    Author create(Author author);

    Author findById(Long id);

    List<Author> searchUsingProvider(AuthorSearchDTO criteria);

    List<Author> searchUsingXml(AuthorSearchDTO criteria);

    long countUsingXml(AuthorSearchDTO criteria);

    int update(Author author);

    int deleteById(Long id);

    int deleteByStatuses(List<String> statuses);
}
