package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectReportVO;

/**
 * 项目审核报告服务接口
 * <p>负责 SQL 质量报告的查询与 PDF 下载，支持按项目获取最新报告或按指定版本获取。
 *
 * @author zhangcs
 */
public interface ProjectReportService {

    /**
     * 获取项目最新一份质量报告
     *
     * @param projectId 项目ID
     * @return 报告视图
     */
    ProjectReportVO getLatestByProjectId(Long projectId);

    /**
     * 根据项目ID和版本ID获取质量报告
     *
     * @param projectId 项目ID
     * @param versionId 版本ID
     * @return 报告视图
     */
    ProjectReportVO getByProjectIdAndVersionId(Long projectId, Long versionId);

    /**
     * 下载项目最新报告的 PDF
     *
     * @param projectId 项目ID
     * @return PDF 字节数组
     */
    byte[] downloadLatestPdf(Long projectId);

    /**
     * 下载指定版本报告的 PDF
     *
     * @param projectId 项目ID
     * @param versionId 版本ID
     * @return PDF 字节数组
     */
    byte[] downloadPdf(Long projectId, Long versionId);
}
