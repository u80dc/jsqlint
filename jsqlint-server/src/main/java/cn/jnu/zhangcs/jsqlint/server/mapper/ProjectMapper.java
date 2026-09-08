package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.Project;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 项目数据接口
 *
 * @author zhangcs
 */
@Mapper
public interface ProjectMapper extends BaseMapper<Project> {
}
