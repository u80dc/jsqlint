package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 项目版本数据接口
 *
 * @author zhangcs
 */
@Mapper
public interface ProjectVersionMapper extends BaseMapper<ProjectVersion> {
}