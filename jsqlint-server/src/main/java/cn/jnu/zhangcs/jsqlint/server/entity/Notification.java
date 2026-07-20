package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知实体
 *
 * @author zhangcs
 */
@Data
@TableName("jsl_notification")
public class Notification {

    /**
     * 通知ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 接收用户ID
     */
    private Long userId;

    /**
     * 通知类型（ANALYSIS_COMPLETE、ALERT_TRIGGERED 等）
     */
    private String type;

    /**
     * 通知标题
     */
    private String title;

    /**
     * 通知内容
     */
    private String content;

    /**
     * 状态: 0-未读, 1-已读
     */
    private Integer status;

    /**
     * 阅读时间
     */
    private LocalDateTime readTime;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}