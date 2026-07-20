package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统配置项实体
 *
 * @author zhangcs
 */
@Data
@TableName("jsl_system_config")
public class SystemConfig {

    /**
     * 配置ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 配置分组
     */
    private String configGroup;

    /**
     * 配置键
     */
    private String configKey;

    /**
     * 配置值
     */
    private String configValue;

    /**
     * 值类型: STRING/INTEGER/BOOLEAN/JSON
     */
    private String valueType;

    /**
     * 配置说明
     */
    private String description;

    /**
     * 最近操作人ID
     */
    private Long operatorId;

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
