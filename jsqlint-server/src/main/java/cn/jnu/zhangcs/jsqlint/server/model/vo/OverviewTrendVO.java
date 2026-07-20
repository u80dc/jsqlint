package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverviewTrendVO {

    private String period;

    private List<String> labels;

    private List<Integer> safe;

    private List<Integer> warning;

    private List<Integer> danger;
}
