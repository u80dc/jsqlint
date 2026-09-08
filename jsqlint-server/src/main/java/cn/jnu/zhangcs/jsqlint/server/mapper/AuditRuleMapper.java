package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.AuditRule;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审核规则数据接口
 *
 * @author zhangcs
 */
@Mapper
public interface AuditRuleMapper extends BaseMapper<AuditRule> {
}
