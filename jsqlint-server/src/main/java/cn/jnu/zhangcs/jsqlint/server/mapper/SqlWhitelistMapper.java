package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.SqlWhitelist;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * SQL 白名单 Mapper
 *
 * @author zhangcs
 */
@Mapper
public interface SqlWhitelistMapper extends BaseMapper<SqlWhitelist> {
}
