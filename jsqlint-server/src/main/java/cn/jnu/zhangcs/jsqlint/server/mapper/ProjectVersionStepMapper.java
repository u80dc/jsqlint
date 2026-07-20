package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.ProjectVersionStep;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 项目版本执行步骤数据接口
 *
 * @author zhangcs
 */
@Mapper
public interface ProjectVersionStepMapper extends BaseMapper<ProjectVersionStep> {
}
