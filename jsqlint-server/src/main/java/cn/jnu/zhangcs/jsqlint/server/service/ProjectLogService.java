package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectOperationType;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectLog;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 项目日志服务接口
 *
 * @author zhangcs
 */
public interface ProjectLogService {

    /**
     * 记录项目操作日志
     *
     * @param projectId        项目ID
     * @param operationType    操作类型
     * @param operationUserId  操作用户ID
     * @param operationMessage 操作信息
     * @param relatedId        关联资源ID
     */
    void log(@NonNull Long projectId,
             @NonNull ProjectOperationType operationType,
             @NonNull Long operationUserId,
             @NonNull String operationMessage,
             @Nullable Long relatedId);

    /**
     * 记录项目操作日志（带IP地址）
     *
     * @param projectId        项目ID
     * @param operationType    操作类型
     * @param operationUserId  操作用户ID
     * @param operationMessage 操作信息
     * @param relatedId        关联资源ID
     * @param ipAddress        IP地址
     */
    void log(@NonNull Long projectId,
             @NonNull ProjectOperationType operationType,
             @NonNull Long operationUserId,
             @NonNull String operationMessage,
             @Nullable Long relatedId,
             @Nullable String ipAddress);

    /**
     * 记录项目操作日志（完整参数）
     *
     * @param projectId        项目ID
     * @param operationType    操作类型
     * @param operationUserId  操作用户ID
     * @param operationMessage 操作信息
     * @param relatedId        关联资源ID
     * @param ipAddress        IP地址
     * @param extraInfo        额外信息JSON
     */
    void log(@NonNull Long projectId,
             @NonNull ProjectOperationType operationType,
             @NonNull Long operationUserId,
             @NonNull String operationMessage,
             @Nullable Long relatedId,
             @Nullable String ipAddress,
             @Nullable String extraInfo);

    /**
     * 分页查询项目日志
     *
     * @param page            当前页
     * @param size            每页大小
     * @param projectId       项目ID
     * @param operationType   操作类型
     * @param operationUserId 操作用户ID
     * @param startTime       开始时间
     * @param endTime         结束时间
     * @return 分页结果
     */
    @NonNull
    Page<ProjectLog> getPage(int page,
                             int size,
                             @Nullable Long projectId,
                             @Nullable String operationType,
                             @Nullable Long operationUserId,
                             @Nullable String startTime,
                             @Nullable String endTime);
}
