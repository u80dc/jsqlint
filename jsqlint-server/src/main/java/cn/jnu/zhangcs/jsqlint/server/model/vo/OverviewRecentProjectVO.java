package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverviewRecentProjectVO {

    private Long projectId;

    private String projectName;

    private Long versionId;

    private String ref;

    private String commitId;

    private String commitMessage;

    private Integer status;

    private String statusDescription;

    private LocalDateTime analysisTime;
}
