package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.SqlChangeLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SQL 变更日志 Mapper
 *
 * @author zhangcs
 */
@Mapper
public interface SqlChangeLogMapper extends BaseMapper<SqlChangeLog> {

    /**
     * 批量插入变更日志
     */
    void batchInsert(@Param("logs") List<SqlChangeLog> logs);

}
