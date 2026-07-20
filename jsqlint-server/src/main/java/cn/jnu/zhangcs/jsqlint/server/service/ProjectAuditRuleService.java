package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.entity.AuditRule;
import cn.jnu.zhangcs.jsqlint.server.model.dto.ProjectAuditRuleDTO;

import java.util.List;

/**
 * 项目审核规则业务接口
 * <p>负责项目级审核规则的增删改查与启停，规则 code 在生效范围内全局唯一，每次变更落版本快照。
 *
 * @author zhangcs
 */
public interface ProjectAuditRuleService {

    /**
     * 分页查询项目级规则列表
     *
     * @param projectId 项目ID
     * @param page      页码
     * @param pageSize  每页大小
     * @param name      规则名称（模糊）
     * @param category  规则分类（可选）
     * @param enabled   是否启用（可选）
     * @return 规则列表
     */
    List<AuditRule> listRules(Long projectId, int page, int pageSize, String name, String category, Boolean enabled);

    /**
     * 统计项目级规则数量
     *
     * @param projectId 项目ID
     * @param name      规则名称（模糊）
     * @param category  规则分类（可选）
     * @param enabled   是否启用（可选）
     * @return 规则总数
     */
    long countRules(Long projectId, String name, String category, Boolean enabled);

    /**
     * 获取项目级规则详情
     *
     * @param projectId 项目ID
     * @param id        规则ID
     * @return 规则详情
     */
    AuditRule getRule(Long projectId, Long id);

    /**
     * 新增项目级规则
     *
     * @param projectId 项目ID
     * @param dto       规则信息
     * @param userId    创建人ID
     * @return 新建的规则
     */
    AuditRule createRule(Long projectId, ProjectAuditRuleDTO dto, Long userId);

    /**
     * 更新项目级规则
     *
     * @param projectId 项目ID
     * @param id        规则ID
     * @param dto       规则信息
     * @param userId    操作人ID
     * @return 更新后的规则
     */
    AuditRule updateRule(Long projectId, Long id, ProjectAuditRuleDTO dto, Long userId);

    /**
     * 删除项目级规则
     *
     * @param projectId 项目ID
     * @param id        规则ID
     */
    void deleteRule(Long projectId, Long id);

    /**
     * 启用或禁用项目级规则
     *
     * @param projectId 项目ID
     * @param id        规则ID
     * @param enabled   是否启用
     * @param userId    操作人ID
     * @return 更新后的规则
     */
    AuditRule toggleRule(Long projectId, Long id, boolean enabled, Long userId);
}
