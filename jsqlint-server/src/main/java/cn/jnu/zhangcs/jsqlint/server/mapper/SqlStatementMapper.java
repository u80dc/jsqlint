package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.SqlStatement;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * SQL 语句解析结果数据接口
 *
 * @author zhangcs
 */
@Mapper
public interface SqlStatementMapper extends BaseMapper<SqlStatement> {

}
