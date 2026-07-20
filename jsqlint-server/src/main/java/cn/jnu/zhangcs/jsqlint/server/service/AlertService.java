package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.entity.AlertInstance;
import cn.jnu.zhangcs.jsqlint.server.entity.AlertRule;
import cn.jnu.zhangcs.jsqlint.server.entity.ScheduledTask;
import cn.jnu.zhangcs.jsqlint.server.model.dto.AlertRuleDTO;
import cn.jnu.zhangcs.jsqlint.server.model.dto.AlertRuleUpdateDTO;

import java.util.List;

/**
 * 告警服务接口
 * <p>负责告警规则的增删改查、告警实例的管理与状态流转，以及在审核完成或任务失败时触发告警评估。
 *
 * @author zhangcs
 */
public interface AlertService {

    /**
     * 查询告警规则列表
     *
     * @param projectId 项目ID（可选，为空表示全局）
     * @param ruleType 规则类型（可选）
     * @param enabled  是否启用（可选）
     * @return 告警规则列表
     */
    List<AlertRule> getRules(Long projectId, String ruleType, Boolean enabled);

    /**
     * 创建告警规则
     *
     * @param dto    告警规则信息
     * @param userId 创建人ID
     * @return 新建的告警规则
     */
    AlertRule addRule(AlertRuleDTO dto, Long userId);

    /**
     * 更新告警规则
     *
     * @param id  规则ID
     * @param dto 规则更新信息
     * @return 更新后的告警规则
     */
    AlertRule updateRule(Long id, AlertRuleUpdateDTO dto);

    /**
     * 删除告警规则
     *
     * @param id 规则ID
     */
    void deleteRule(Long id);

    /**
     * 启用或禁用告警规则
     *
     * @param id      规则ID
     * @param enabled 是否启用
     * @return 更新后的告警规则
     */
    AlertRule toggleRule(Long id, boolean enabled);

    /**
     * 查询告警实例列表
     *
     * @param projectId 项目ID（可选）
     * @param severity  告警级别（可选）
     * @param status    告警状态（可选）
     * @return 告警实例列表
     */
    List<AlertInstance> getInstances(Long projectId, String severity, String status);

    /**
     * 更新告警实例状态（如确认 ACKED、解决 RESOLVED 等）
     *
     * @param id     实例ID
     * @param status 目标状态
     * @param userId 操作人ID
     */
    void updateInstanceStatus(Long id, String status, Long userId);

    /**
     * 评估审核完成事件，命中规则的生成或累计告警实例并通知接收人
     *
     * @param projectId 项目ID
     * @param versionId 版本ID
     * @param taskId    触发任务ID
     * @return 本次触发的告警实例数量
     */
    int evaluateAuditCompleted(Long projectId, Long versionId, Long taskId);

    /**
     * 评估任务失败事件，命中规则的生成或累计告警实例并通知接收人
     *
     * @param failedTask   失败的任务
     * @param errorMessage 失败原因
     * @return 本次触发的告警实例数量
     */
    int evaluateTaskFailed(ScheduledTask failedTask, String errorMessage);
}
