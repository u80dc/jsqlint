package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.Workspace;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工作空间表数据接口
 *
 * @author zhangcs
 */
@Mapper
public interface WorkspaceMapper extends BaseMapper<Workspace> {

}