package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.analysis.AnalyzeResult;
import cn.jnu.zhangcs.jsqlint.server.entity.SqlStatement;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SqlChangeLogVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SqlConstructionDetailVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SqlPropagationPathVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SqlStatementVO;

import java.util.List;

/**
 * SQL 语句存储服务接口
 *
 * @author zhangcs
 */
public interface SqlStatementService {

    /**
     * 保存 SQL 语句列表
     */
    void saveSqlStatements(List<SqlStatement> statements);

    /**
     * 替换指定项目版本的分析结果。
     * <p>同一版本只保留一份最新分析结果；替换时会清理依赖旧 SQL 记录的审核结果和变更日志。</p>
     */
    void replaceVersionAnalysis(Long projectId, Long versionId, AnalyzeResult result);

    /**
     * 根据版本ID查询所有SQL语句
     */
    List<SqlStatementVO> getSqlStatementsByVersion(Long versionId);

    /**
     * 根据项目ID和版本ID查询
     */
    List<SqlStatementVO> getSqlStatementsByProjectAndVersion(Long projectId, Long versionId);

    /**
     * 查询 SQL 构造过程详情
     */
    SqlConstructionDetailVO getConstructionDetail(Long id);

    /**
     * 查询 SQL 参数传播路径。
     *
     * <p>返回指定 SQL 语句（需属于指定项目版本）每个参数的污点传播链路。
     *
     * @param id        SQL 语句记录 ID
     * @param versionId 项目版本 ID，用于校验归属
     * @return 传播路径视图
     */
    SqlPropagationPathVO getPropagationPaths(Long id, Long versionId);

    /**
     * 比较两个版本的SQL差异
     */
    List<SqlChangeLogVO> compareTwoVersions(Long projectId, Long fromVersionId, Long toVersionId);

    /**
     * 根据SQL hash查询
     */
    List<SqlStatementVO> getSqlStatementsByHash(String sqlHash);

    /**
     * 删除指定版本的所有SQL
     */
    void deleteSqlStatementsByVersion(Long versionId);

    /**
     * 查询项目的所有变更历史
     */
    List<SqlChangeLogVO> getChangeHistory(Long projectId);

    /**
     * 查询指定版本变更的详细信息
     */
    List<SqlChangeLogVO> getChangesByVersions(Long projectId, Long fromVersionId, Long toVersionId);

    /**
     * 根据项目版本获取
     *
     * @param projectId 项目
     * @param versionId 版本
     * @return 返回列表
     */
    List<SqlStatement> getByProjectAndVersion(Long projectId, Long versionId);

}
