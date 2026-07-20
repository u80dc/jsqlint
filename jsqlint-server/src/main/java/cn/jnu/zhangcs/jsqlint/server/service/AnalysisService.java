package cn.jnu.zhangcs.jsqlint.server.service;

/**
 * 项目分析统一接口
 *
 * @author zhangcs
 */
public interface AnalysisService {

    /**
     * 同步项目
     *
     * @param projectId 项目
     * @param userId    操作用户
     */
    void sync(Long projectId, Long userId);

    /**
     * 编译项目
     *
     * @param projectId 项目
     * @param userId    操作用户
     */
    void compile(Long projectId, Long userId);

    /**
     * 分析项目
     *
     * @param projectId 项目
     * @param userId    操作用户
     */
    void analyze(Long projectId, Long userId);

    /**
     * 审核项目
     *
     * @param projectId 项目
     */
    void audit(Long projectId, Long userId);

    /**
     * 生成报告
     *
     * @param projectId 项目
     */
    Long report(Long projectId, Long userId);

    /**
     * 执行项目完整流程。
     *
     * @param projectId 项目
     * @param userId    操作用户
     * @return 任务提交成功返回 true
     */
    boolean execute(Long projectId, Long userId);

}
