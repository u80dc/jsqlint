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
     * 根据类型获取仓库
     */
    List<MavenRepository> getByType(Integer type, @Nullable Integer status);
}