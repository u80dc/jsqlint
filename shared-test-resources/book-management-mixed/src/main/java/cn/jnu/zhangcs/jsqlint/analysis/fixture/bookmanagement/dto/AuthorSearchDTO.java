package cn.jnu.zhangcs.jsqlint.analysis.fixture.bookmanagement.dto;

import java.util.ArrayList;
import java.util.List;

public class AuthorSearchDTO {

    private String status;

    private String country;

    private List<Long> ids = new ArrayList<>();

    private List<String> keywords = new ArrayList<>();

    private String orderBy;

    private boolean descending;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public List<Long> getIds() {
        return ids;
    }

    public void setIds(List<Long> ids) {
        this.ids = ids == null ? new ArrayList<>() : ids;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords == null ? new ArrayList<>() : keywords;
    }

    public String getOrderBy() {
        return orderBy;
    }

    public void setOrderBy(String orderBy) {
        this.orderBy = orderBy;
    }

    public boolean isDescending() {
        return descending;
    }

    public void setDescending(boolean descending) {
        this.descending = descending;
    }
}
