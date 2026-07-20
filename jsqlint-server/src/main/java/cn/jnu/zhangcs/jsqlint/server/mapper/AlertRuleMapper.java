package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.AlertRule;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 告警规则数据接口
 *
 * @author zhangcs
 */
@Mapper
public interface AlertRuleMapper extends BaseMapper<AlertRule> {
}
