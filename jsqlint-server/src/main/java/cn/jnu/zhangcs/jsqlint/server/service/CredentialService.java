package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.entity.Credential;

import java.util.List;

/**
 * Git 凭证服务接口
 * <p>负责 Git 访问凭证的增删改查、启停以及用户可选凭证的筛选（私有凭证与公开凭证）。
 *
 * @author zhangcs
 */
public interface CredentialService {

    /**
     * 创建凭证
     *
     * @param credential 凭证信息
     * @return 新建的凭证
     */
    Credential createCredential(Credential credential);

    /**
     * 更新凭证
     *
     * @param credential 凭证信息
     * @return 更新后的凭证
     */
    Credential updateCredential(Credential credential);

    /**
     * 删除凭证（软删除）
     *
     * @param id 凭证ID
     */
    void deleteCredential(Long id);

    /**
     * 根据ID获取凭证
     *
     * @param id 凭证ID
     * @return 凭证信息
     */
    Credential getCredentialById(Long id);

    /**
     * 查询凭证列表
     *
     * @param status 状态过滤（可选）
     * @return 凭证列表
     */
    List<Credential> listCredentials(Integer status);

    /**
     * 分页查询凭证列表
     *
     * @param status   状态过滤（可选）
     * @param page     页码，从 1 开始
     * @param pageSize 每页数量
     * @return 分页结果（records + total）
     */
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<Credential> getCredentialPage(Integer status, int page, int pageSize);

    /**
     * 启用或禁用凭证
     *
     * @param id     凭证ID
     * @param status 目标状态
     * @return 更新后的凭证
     */
    Credential toggleEnabled(Long id, Integer status);

    /**
     * 获取用户可选的凭证，包含私人和他人公开的
     *
     * @param userId 用户
     * @return 返回凭证列表
     */
    List<Credential> listOptional(Long userId);

}