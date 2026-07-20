package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * SQL 白名单 VO
 */
@Data
public class SqlWhitelistVO {

    private Long id;

    private Long projectId;

    private String sqlFingerprint;

    private String sqlSample;

    private Long ruleId;

    private String ruleName;

    private String reason;

    private String createUserName;

    private LocalDateTime createTime;
}
