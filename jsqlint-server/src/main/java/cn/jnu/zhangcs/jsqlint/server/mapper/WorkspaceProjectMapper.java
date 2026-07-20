package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.WorkspaceProject;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工作空间与项目关系数据接口
 *
 * @author zhangcs
 */
@Mapper
public interface WorkspaceProjectMapper extends BaseMapper<WorkspaceProject> {

}