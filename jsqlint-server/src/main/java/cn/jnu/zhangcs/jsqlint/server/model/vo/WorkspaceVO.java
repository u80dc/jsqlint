package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 工作空间VO
 *
 * @author zhangcs
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceVO {

    private Long id;

    private String name;

    private Long creatorId;

    private String creatorName;

    private Integer projectCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}