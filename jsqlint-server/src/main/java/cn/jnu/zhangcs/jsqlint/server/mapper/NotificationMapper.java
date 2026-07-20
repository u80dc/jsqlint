package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.Notification;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站内通知数据接口
 *
 * @author zhangcs
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}