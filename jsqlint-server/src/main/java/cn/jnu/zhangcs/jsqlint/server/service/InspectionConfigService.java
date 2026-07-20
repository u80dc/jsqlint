package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.entity.InspectionConfig;

import java.util.List;

/**
 * 巡检配置业务接口
 *
 * <p>负责项目定时巡检配置的创建、查询、启停、删除、调度时间计算以及巡检结果回写。
 * 调度器通过该接口查询到期配置并抢占执行权，避免同一巡检配置被重复触发。</p>
 *
 * @author zhangcs
 */
public interface InspectionConfigService {

    /**
     * 保存或更新项目巡检配置。
     *
     * @param projectId 项目ID
     * @param cronExpr  Cron表达式
     * @param enabled   是否启用巡检
     * @param operatorId 操作人ID
     * @return 保存后的巡检配置
     */
    InspectionConfig saveOrUpdate(Long projectId, String cronExpr, Boolean enabled, Long operatorId);

    /**
     * 根据项目ID查询巡检配置。
     *
     * @param projectId 项目ID
     * @return 巡检配置；不存在时返回 null
     */
    InspectionConfig getByProjectId(Long projectId);

    /**
     * 查询巡检配置列表。
     *
     * @param enabled 是否启用；为 null 时不按启用状态过滤
     * @return 巡检配置列表
     */
    List<InspectionConfig> list(Boolean enabled);

    /**
     * 启用或禁用项目巡检配置。
     *
     * @param projectId  项目ID
     * @param enabled    是否启用
     * @param operatorId 操作人ID
     */
    void setEnabled(Long projectId, boolean enabled, Long operatorId);

    /**
     * 删除项目巡检配置。
     *
     * @param projectId  项目ID
     * @param operatorId 操作人ID
     */
    void deleteByProjectId(Long projectId, Long operatorId);

    /**
     * 查询当前已到执行时间的巡检配置。
     *
     * @param limit 最大返回数量
     * @return 到期且可调度的巡检配置列表
     */
    List<InspectionConfig> findDueConfigs(int limit);

    /**
     * 尝试抢占到期巡检配置的执行权。
     *
     * @param configId 巡检配置ID
     * @return 抢占成功返回 true；配置已被其他执行器抢占或状态不满足时返回 false
     */
    boolean tryClaimDue(Long configId);

    /**
     * 根据 Cron 表达式计算并更新下一次执行时间。
     *
     * @param configId 巡检配置ID
     * @param cronExpr Cron表达式
     */
    void scheduleNext(Long configId, String cronExpr);

    /**
     * 回写项目巡检执行结果。
     *
     * @param projectId 项目ID
     * @param status    执行状态
     * @param message   执行结果或失败原因
     */
    void markResult(Long projectId, String status, String message);

    /**
     * 根据 Cron 表达式计算下一次执行时间。
     *
     * @param cronExpr Cron表达式
     * @return 下一次执行时间的时间戳；具体单位以实现类约定为准
     */
    Long computeNextExecTime(String cronExpr);
}
