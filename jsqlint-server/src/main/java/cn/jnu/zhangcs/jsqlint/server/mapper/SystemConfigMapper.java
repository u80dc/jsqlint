package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.SystemConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统配置数据接口
 *
 * @author zhangcs
 */
@Mapper
public interface SystemConfigMapper extends BaseMapper<SystemConfig> {
}
