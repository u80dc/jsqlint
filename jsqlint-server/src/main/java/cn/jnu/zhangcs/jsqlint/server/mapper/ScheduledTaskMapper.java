package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.ScheduledTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 定时任务数据接口
 *
 * @author zhangcs
 */
@Mapper
public interface ScheduledTaskMapper extends BaseMapper<ScheduledTask> {

}
