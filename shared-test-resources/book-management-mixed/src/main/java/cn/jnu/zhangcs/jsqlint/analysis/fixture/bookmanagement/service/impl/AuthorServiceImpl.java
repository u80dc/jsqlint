package cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.service.impl;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.dto.AuthorSearchDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.entity.Author;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.mapper.AuthorMapper;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.service.AuthorService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class AuthorServiceImpl implements AuthorService {

    private final AuthorMapper authorMapper;

    public AuthorServiceImpl(AuthorMapper authorMapper) {
        this.authorMapper = authorMapper;
    }

    @Override
    public Author create(Author author) {
        authorMapper.insert(author);
        return author;
    }

    @Override
    @Transactional(readOnly = true)
    public Author findById(Long id) {
        return authorMapper.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Author> searchUsingProvider(AuthorSearchDTO criteria) {
        return authorMapper.searchUsingProvider(criteria);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Author> searchUsingXml(AuthorSearchDTO criteria) {
        return authorMapper.searchUsingXml(criteria);
    }

    @Override
    @Transactional(readOnly = true)
    public long countUsingXml(AuthorSearchDTO criteria) {
        return authorMapper.countUsingXml(criteria);
    }

    @Override
    public int update(Author author) {
        return authorMapper.updateUsingProvider(author);
    }

    @Override
    public int deleteById(Long id) {
        return authorMapper.deleteById(id);
    }

    @Override
    public int deleteByStatuses(List<String> statuses) {
        return authorMapper.deleteByStatusesUsingProvider(statuses);
    }
}
