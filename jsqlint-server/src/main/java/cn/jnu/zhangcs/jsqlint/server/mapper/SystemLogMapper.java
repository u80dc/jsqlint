package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.SystemLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统日志数据接口
 *
 * @author zhangcs
 */
@Mapper
public interface SystemLogMapper extends BaseMapper<SystemLog> {

}
