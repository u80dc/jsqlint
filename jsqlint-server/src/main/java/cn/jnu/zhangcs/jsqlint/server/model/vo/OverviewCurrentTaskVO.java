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
public class OverviewCurrentTaskVO {

    private Long id;

    private Long projectId;

    private String projectName;

    private Long versionId;

    private Integer type;

    private String typeDescription;

    private Integer status;

    private String statusDescription;

    private Long nextExecTime;

    private Integer retryCount;

    private Integer maxRetries;

    private String errorMessage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
