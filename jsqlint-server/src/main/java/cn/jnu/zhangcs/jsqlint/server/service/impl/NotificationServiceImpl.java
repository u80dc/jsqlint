package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.entity.Notification;
import cn.jnu.zhangcs.jsqlint.server.mapper.NotificationMapper;
import cn.jnu.zhangcs.jsqlint.server.service.NotificationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationMapper notificationMapper;

    @Override
    public List<Notification> listByUserId(Long userId) {
        LambdaQueryWrapper<Notification> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Notification::getUserId, userId)
                .orderByDesc(Notification::getCreateTime);
        return notificationMapper.selectList(queryWrapper);
    }

    @Override
    public Notification getById(Long id) {
        return notificationMapper.selectById(id);
    }

    @Override
    @Transactional
    public void markAsRead(Long id) {
        Notification notification = notificationMapper.selectById(id);
        if (notification != null && notification.getStatus() == 0) {
            notification.setStatus(1);
            notification.setReadTime(LocalDateTime.now());
            notificationMapper.updateById(notification);
        }
    }

    @Override
    @Transactional
    public void markAllAsRead(Long userId) {
        Notification notification = new Notification();
        notification.setStatus(1);
        notification.setReadTime(LocalDateTime.now());
        LambdaQueryWrapper<Notification> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Notification::getUserId, userId)
                .eq(Notification::getStatus, 0);
        notificationMapper.update(notification, queryWrapper);
    }

    @Override
    @Transactional
    public void deleteByIdAndUserId(Long id, Long userId) {
        LambdaQueryWrapper<Notification> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Notification::getId, id)
                .eq(Notification::getUserId, userId);
        notificationMapper.delete(queryWrapper);
    }

    @Override
    public int getUnreadCount(Long userId) {
        LambdaQueryWrapper<Notification> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Notification::getUserId, userId)
                .eq(Notification::getStatus, 0);
        return notificationMapper.selectCount(queryWrapper).intValue();
    }

    @Override
    @Transactional
    public Notification create(Long userId, String type, String title, String content) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setStatus(0);
        notificationMapper.insert(notification);
        return notification;
    }
}
