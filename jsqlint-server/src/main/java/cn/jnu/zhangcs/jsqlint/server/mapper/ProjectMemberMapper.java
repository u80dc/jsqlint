package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.ProjectMember;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 项目成员关系表数据接口
 *
 * @author zhangcs
 */
@Mapper
public interface ProjectMemberMapper extends BaseMapper<ProjectMember> {
}