package cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture;

public class Book {

    private String name;
    private String author;
    private Long categoryId;
    private Integer status;
    private Integer offset;
    private Integer limit;

    public String getName() {
        return name;
    }

    public String getAuthor() {
        return author;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public Integer getStatus() {
        return status;
    }

    public Integer getOffset() {
        return offset;
    }

    public Integer getLimit() {
        return limit;
    }
}
