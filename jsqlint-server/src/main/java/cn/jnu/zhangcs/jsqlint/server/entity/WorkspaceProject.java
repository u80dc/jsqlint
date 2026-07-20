package cn.jnu.zhangcs.jsqlint.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作空间与项目关系实体
 *
 * @author zhangcs
 */
@Data
@TableName("jsl_workspace_project")
public class WorkspaceProject {

    /**
     * 关系ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 工作空间ID
     */
    private Long workspaceId;

    /**
     * 项目ID
     */
    private Long projectId;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}