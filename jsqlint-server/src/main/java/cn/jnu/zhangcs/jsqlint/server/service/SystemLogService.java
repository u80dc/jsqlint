package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.common.enums.SystemOperationType;
import cn.jnu.zhangcs.jsqlint.server.entity.SystemLog;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 系统日志服务接口
 *
 * @author zhangcs
 */
public interface SystemLogService {

    /**
     * 记录系统操作日志
     *
     * @param type      操作类型
     * @param userId    操作用户ID
     * @param message   操作信息
     * @param relatedId 关联资源ID
     */
    void log(@NonNull SystemOperationType type,
             @NonNull Long userId,
             @NonNull String message,
             @Nullable Long relatedId);

    /**
     * 记录系统操作日志（带IP地址）
     *
     * @param type      操作类型
     * @param userId    操作用户ID
     * @param message   操作信息
     * @param relatedId 关联资源ID
     * @param ipAddress IP地址
     */
    void log(@NonNull SystemOperationType type,
             @NonNull Long userId,
             @NonNull String message,
             @Nullable Long relatedId,
             @Nullable String ipAddress);

    /**
     * 记录系统操作日志（完整参数）
     *
     * @param type      操作类型
     * @param userId    操作用户ID
     * @param message   操作信息
     * @param relatedId 关联资源ID
     * @param ipAddress IP地址
     * @param extraInfo 额外信息JSON
     */
    void log(@NonNull SystemOperationType type,
             @NonNull Long userId,
             @NonNull String message,
             @Nullable Long relatedId,
             @Nullable String ipAddress,
             @Nullable String extraInfo);

    /**
     * 分页查询系统日志
     *
     * @param page      当前页
     * @param size      每页大小
     * @param type      操作类型
     * @param userId    操作用户ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 分页结果
     */
    @NonNull
    Page<SystemLog> getPage(int page,
                            int size,
                            @Nullable String type,
                            @Nullable Long userId,
                            @Nullable String startTime,
                            @Nullable String endTime);

    /**
     * 根据关联资源ID查询日志
     *
     * @param relatedId 关联资源ID
     * @return 日志列表
     */
    @NonNull
    Page<SystemLog> getByRelatedId(int page, int size, @NonNull Long relatedId);
}
