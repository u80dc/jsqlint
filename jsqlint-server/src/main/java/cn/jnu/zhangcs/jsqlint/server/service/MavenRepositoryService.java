package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.entity.MavenRepository;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Maven 仓库服务接口
 * <p>负责 Maven 依赖/插件仓库配置的增删改查，为项目编译提供仓库来源。
 *
 * @author zhangcs
 */
public interface MavenRepositoryService {

    /**
     * 创建仓库
     */
    MavenRepository create(MavenRepository repository);

    /**
     * 更新仓库
     */
    MavenRepository update(MavenRepository repository);

    /**
     * 删除仓库
     */
    void delete(Long id);

    /**
     * 获取仓库
     */
    MavenRepository getById(Long id);

    /**
     * 获取仓库列表
     * @param status 过滤状态，为空时不过滤
     */
    List<MavenRepository> list(@Nullable Integer status);

    /**
     * 分页获取仓库列表
     *
     * @param type     仓库类型（可选，为空表示全部）
     * @param status   过滤状态（可选）
     * @param page     页码，从 1 开始
     * @param pageSize 每页数量
     * @return 分页结果（records + total）
     */
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<MavenRepository> getRepositoryPage(
            @Nullable Integer type, @Nullable Integer status, int page, int pageSize);

    /**
     * 根据类型获取仓库
     */
    List<MavenRepository> getByType(Integer type, @Nullable Integer status);
}