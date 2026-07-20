package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.entity.AuditRule;

import java.util.List;

/**
 * 审核规则服务接口
 * <p>负责全局审核规则的增删改查、启用切换、Drools 语法校验以及重载，并提供项目可用生效规则的叠加查询（全局规则 + 项目规则）。
 *
 * @author zhangcs
 */
public interface AuditRuleService {

    /**
     * 分页查询规则列表
     *
     * @param page     页码
     * @param pageSize 每页大小
     * @param name     规则名称（模糊查询）
     * @param category 规则分类
     * @param enabled  是否启用
     * @return 规则列表
     */
    List<AuditRule> listRules(int page, int pageSize, String name, String category, Boolean enabled);

    /**
     * 查询项目审核时可用的规则列表，包含全局规则和指定项目的特有规则。
     *
     * @param projectId 项目ID
     * @param enabled   是否启用
     * @return 规则列表
     */
    List<AuditRule> listEffectiveRules(Long projectId, Boolean enabled);

    /**
     * 获取规则总数
     *
     * @param name     规则名称
     * @param category 规则分类
     * @param enabled 是否启用
     * @return 总数
     */
    long countRules(String name, String category, Boolean enabled);

    /**
     * 获取规则详情
     *
     * @param id 规则ID
     * @return 规则详情
     */
    AuditRule getRuleById(Long id);

    /**
     * 新增规则
     *
     * @param rule 规则信息
     * @param userId 创建用户ID
     * @return 新增的规则
     */
    AuditRule createRule(AuditRule rule, Long userId);

    /**
     * 更新规则
     *
     * @param id   规则ID
     * @param rule 规则信息
     * @return 更新后的规则
     */
    AuditRule updateRule(Long id, AuditRule rule);

    /**
     * 删除规则
     *
     * @param id 规则ID
     */
    void deleteRule(Long id);

    /**
     * 启用/禁用规则
     *
     * @param id      规则ID
     * @param enabled 是否启用
     */
    void toggleRule(Long id, boolean enabled);

    /**
     * 验证规则语法
     *
     * @param ruleContent 规则内容
     * @return 验证结果
     */
    boolean validateRule(String ruleContent);

    /**
     * 校验当前启用规则能否被审核工具编译
     */
    void reloadRules();
}