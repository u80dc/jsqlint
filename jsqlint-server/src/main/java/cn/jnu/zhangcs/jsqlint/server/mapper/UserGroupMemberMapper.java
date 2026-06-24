package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.UserGroupMember;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户组成员数据接口
 *
 * @author zhangcs
 */
@Mapper
public interface UserGroupMemberMapper extends BaseMapper<UserGroupMember> {
}