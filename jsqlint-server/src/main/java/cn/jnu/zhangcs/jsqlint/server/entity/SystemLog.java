package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统操作日志实体
 *
 * @author zhangcs
 */
@Data
@TableName("jsl_system_log")
public class SystemLog {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 操作类型（USER_CREATE/USER_UPDATE/USER_DELETE/CREDENTIAL_CREATE 等）
     */
    private String operationType;

    /**
     * 操作用户ID
     */
    private Long operationUserId;

    /**
     * 操作用户名（冗余字段）
     */
    private String operationUserName;

    /**
     * 操作信息文本
     */
    private String operationMessage;

    /**
     * 关联的资源ID（用户ID、凭证ID、规则ID等）
     */
    private Long relatedId;

    /**
     * 操作时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime operationTime;

    /**
     * IP 地址
     */
    private String ipAddress;

    /**
     * 额外信息（JSON）
     */
    private String extraInfo;
}
