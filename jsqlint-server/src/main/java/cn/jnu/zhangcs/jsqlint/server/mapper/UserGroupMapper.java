package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.UserGroup;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户组数据接口
 *
 * @author zhangcs
 */
@Mapper
public interface UserGroupMapper extends BaseMapper<UserGroup> {

}