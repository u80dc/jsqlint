package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserVO {

    private Long id;

    private String username;

    private String realName;

    private String email;

    private String phone;

    private Integer status;

    private String role;

    private String roleDescription;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private List<UserGroupVO> groups;
}