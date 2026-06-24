package cn.jnu.zhangcs.jsqlint.analysis.fixture.jpa.dto;

import java.util.ArrayList;
import java.util.List;

public class UserStatusDeleteDTO {

    private List<String> statuses = new ArrayList<>();

    private boolean requireInactive = true;

    public List<String> getStatuses() {
        return statuses;
    }

    public void setStatuses(List<String> statuses) {
        this.statuses = statuses == null ? new ArrayList<>() : new ArrayList<>(statuses);
    }

    public boolean isRequireInactive() {
        return requireInactive;
    }

    public void setRequireInactive(boolean requireInactive) {
        this.requireInactive = requireInactive;
    }
}
