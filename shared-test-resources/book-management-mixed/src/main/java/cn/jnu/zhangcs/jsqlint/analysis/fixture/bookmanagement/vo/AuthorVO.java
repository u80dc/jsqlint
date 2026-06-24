package cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.vo;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.entity.Author;

public class AuthorVO {

    private Long id;
    private String name;
    private String country;
    private String status;

    public static AuthorVO from(Author author) {
        if (author == null) {
            return null;
        }
        AuthorVO vo = new AuthorVO();
        vo.id = author.getId();
        vo.name = author.getName();
        vo.country = author.getCountry();
        vo.status = author.getStatus();
        return vo;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCountry() {
        return country;
    }

    public String getStatus() {
        return status;
    }
}
