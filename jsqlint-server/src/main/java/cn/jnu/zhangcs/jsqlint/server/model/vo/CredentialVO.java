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
public class CredentialVO {

    private Long id;

    private String name;

    private Integer visibility;

    private String visibilityDescription;

    private Integer status;

    private String account;

    private String token;

    private String password;

    private Long createUserId;

    private String createUserName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}