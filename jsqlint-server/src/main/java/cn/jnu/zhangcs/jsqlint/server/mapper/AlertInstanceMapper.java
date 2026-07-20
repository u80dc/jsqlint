package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.AlertInstance;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 告警实例数据接口
 *
 * @author zhangcs
 */
@Mapper
public interface AlertInstanceMapper extends BaseMapper<AlertInstance> {
}
