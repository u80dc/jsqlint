package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import org.apache.ibatis.type.BooleanTypeHandler;

import java.time.LocalDateTime;

/**
 * Maven 仓库配置实体
 *
 * @author zhangcs
 */
@Data
@TableName("jsl_maven_repository")
public class MavenRepository {

    /**
     * 仓库ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 仓库类型: 0(依赖仓库), 1(插件仓库)
     */
    private Integer type;

    /**
     * 仓库唯一ID
     */
    private String repoId;

    /**
     * 仓库名称
     */
    private String name;

    /**
     * 仓库URL
     */
    private String url;

    /**
     * 布局: default/legacy
     */
    private String layout;

    /**
     * releases 是否启用
     */
    @TableField(value = "releases_enabled", typeHandler = BooleanTypeHandler.class)
    private Boolean releasesEnabled = false;

    /**
     * releases 更新策略: daily/always/never
     */
    private String releasesUpdatePolicy;

    /**
     * releases 校验策略: fail/warn
     */
    private String releasesChecksumPolicy;

    /**
     * snapshots 是否启用
     */
    @TableField(value = "snapshots_enabled", typeHandler = BooleanTypeHandler.class)
    private Boolean snapshotsEnabled = false;

    /**
     * snapshots 更新策略: daily/always/never
     */
    private String snapshotsUpdatePolicy;

    /**
     * snapshots 校验策略: fail/warn
     */
    private String snapshotsChecksumPolicy;

    /**
     * 状态（0-禁用，1-启用）
     */
    private Integer status;

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