package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 项目成员VO
 *
 * @author zhangcs
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectMemberVO {

    private Long id;

    private Long projectId;

    private String projectName;

    private Long userId;

    private String account;

    private String username;

    private Integer role;

    private String roleDescription;

    private LocalDateTime joinTime;
}