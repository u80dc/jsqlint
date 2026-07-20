package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.ProjectLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 项目日志数据接口
 *
 * @author zhangcs
 */
@Mapper
public interface ProjectLogMapper extends BaseMapper<ProjectLog> {

}
