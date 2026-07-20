package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.AuditRuleVersion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审核规则版本数据接口
 *
 * @author zhangcs
 */
@Mapper
public interface AuditRuleVersionMapper extends BaseMapper<AuditRuleVersion> {
}