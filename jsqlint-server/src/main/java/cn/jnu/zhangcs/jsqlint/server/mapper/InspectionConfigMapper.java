package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.InspectionConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 项目巡检配置数据接口
 *
 * @author zhangcs
 */
@Mapper
public interface InspectionConfigMapper extends BaseMapper<InspectionConfig> {

}
