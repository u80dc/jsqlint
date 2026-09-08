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
public class ProjectVO {

    private Long id;

    private String name;

    private String description;

    private String gitUrl;

    private String ref;

    /** SQL 方言: mysql/postgresql/oracle */
    private String dialect;

    private Integer enabled;

    private Long createUserId;

    private String createUserName;

    private Long credentialId;

    private Long workspaceId;

    private String workspaceName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // 凭证详细信息
    private String credentialName;

    private Long credentialCreatorId;

    private String credentialCreatorName;

    private Integer credentialVisibility;
}