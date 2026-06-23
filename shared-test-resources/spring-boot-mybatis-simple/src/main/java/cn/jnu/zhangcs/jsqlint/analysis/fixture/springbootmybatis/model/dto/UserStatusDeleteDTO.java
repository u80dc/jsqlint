package cn.jnu.zhangcs.jsqlint.analysis.fixture.springbootmybatis.model.dto;

import java.util.ArrayList;
import java.util.List;

public class UserStatusDeleteDTO {

    private List<String> statuses = new ArrayList<>();

    public List<String> getStatuses() {
        return statuses;
    }

    public void setStatuses(List<String> statuses) {
        this.statuses = statuses == null ? new ArrayList<>() : new ArrayList<>(statuses);
    }
}
