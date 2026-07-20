package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.entity.SqlWhitelist;
import cn.jnu.zhangcs.jsqlint.server.model.dto.SqlWhitelistDTO;

import java.util.List;
import java.util.Set;

/**
 * SQL 白名单服务接口
 *
 * @author zhangcs
 */
public interface SqlWhitelistService {

    /**
     * 分页查询项目白名单
     *
     * @param projectId 项目ID
     * @param page      页码
     * @param pageSize  每页数量
     * @param ruleId    规则ID（可选过滤）
     * @return 白名单列表
     */
    List<SqlWhitelist> list(Long projectId, int page, int pageSize, Long ruleId);

    /**
     * 统计项目白名单总数
     *
     * @param projectId 项目ID
     * @param ruleId    规则ID（可选过滤）
     * @return 总数
     */
    long count(Long projectId, Long ruleId);

    /**
     * 新增白名单
     *
     * @param projectId 项目ID
     * @param dto       白名单 DTO（包含原始 SQL，后端自动计算指纹）
     * @param userId    操作人ID
     * @return 新增的白名单记录
     */
    SqlWhitelist create(Long projectId, SqlWhitelistDTO dto, Long userId);

    /**
     * 删除白名单
     *
     * @param projectId 项目ID
     * @param id        白名单ID
     */
    void delete(Long projectId, Long id);

    /**
     * 加载项目所有白名单键集合，用于审核时批量过滤。
     * <p>
     * 返回的 Set 中每个元素格式为 {@code "sqlFingerprint:ruleId"}
     *
     * @param projectId 项目ID
     * @return 白名单键集合
     */
    Set<String> loadWhitelistSet(Long projectId);
}
