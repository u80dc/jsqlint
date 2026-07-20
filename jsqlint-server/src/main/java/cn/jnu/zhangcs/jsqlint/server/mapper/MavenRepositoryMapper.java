package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.MavenRepository;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Maven 仓库配置数据接口
 *
 * @author zhangcs
 */
@Mapper
public interface MavenRepositoryMapper extends BaseMapper<MavenRepository> {
}