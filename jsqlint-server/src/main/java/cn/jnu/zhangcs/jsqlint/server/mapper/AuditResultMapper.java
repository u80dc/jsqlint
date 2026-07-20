package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.AuditResult;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审核结果数据接口
 *
 * @author zhangcs
 */
@Mapper
public interface AuditResultMapper extends BaseMapper<AuditResult> {
}