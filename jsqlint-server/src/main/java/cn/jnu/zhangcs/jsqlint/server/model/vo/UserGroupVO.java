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
public class UserGroupVO {

    private Long id;

    private String name;

    private String description;

    private Long creatorId;

    private String creatorName;

    private Integer status;

    private Integer memberCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}