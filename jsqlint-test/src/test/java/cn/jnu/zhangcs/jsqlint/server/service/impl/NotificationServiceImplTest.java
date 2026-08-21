package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.entity.Notification;
import cn.jnu.zhangcs.jsqlint.server.mapper.NotificationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NotificationServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationMapper notificationMapper;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    @Test
    void listByUserId_delegatesToMapper() {
        List<Notification> list = List.of(new Notification());
        when(notificationMapper.selectList(any())).thenReturn(list);

        assertThat(notificationService.listByUserId(1L)).isSameAs(list);
    }

    @Test
    void getById_delegatesToMapper() {
        Notification notification = new Notification();
        when(notificationMapper.selectById(7L)).thenReturn(notification);

        assertThat(notificationService.getById(7L)).isSameAs(notification);
    }

    @Test
    void markAsRead_unreadNotification_updatedWithReadTime() {
        Notification notification = new Notification();
        notification.setStatus(0);
        when(notificationMapper.selectById(7L)).thenReturn(notification);

        notificationService.markAsRead(7L);

        assertThat(notification.getStatus()).isEqualTo(1);
        assertThat(notification.getReadTime()).isNotNull();
        verify(notificationMapper).updateById(notification);
    }

    @Test
    void markAsRead_alreadyRead_noDuplicateUpdate() {
        Notification notification = new Notification();
        notification.setStatus(1);
        when(notificationMapper.selectById(7L)).thenReturn(notification);

        notificationService.markAsRead(7L);

        verify(notificationMapper, never()).updateById(any(Notification.class));
    }

    @Test
    void markAsRead_notificationNotFound_noUpdate() {
        when(notificationMapper.selectById(7L)).thenReturn(null);

        notificationService.markAsRead(7L);

        verify(notificationMapper, never()).updateById(any(Notification.class));
    }

    @Test
    void markAllAsRead_updatesBatchByUser() {
        notificationService.markAllAsRead(1L);

        verify(notificationMapper).update(any(Notification.class), any());
    }

    @Test
    void deleteByIdAndUserId_deletesByIdAndUser() {
        notificationService.deleteByIdAndUserId(7L, 1L);

        verify(notificationMapper).delete(any());
    }

    @Test
    void getUnreadCount_returnsIntValue() {
        when(notificationMapper.selectCount(any())).thenReturn(3L);

        assertThat(notificationService.getUnreadCount(1L)).isEqualTo(3);
    }

    @Test
    void create_defaultsToUnreadStatus() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);

        Notification result = notificationService.create(1L, "AUDIT", "标题", "内容");

        verify(notificationMapper).insert(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getType()).isEqualTo("AUDIT");
        assertThat(saved.getTitle()).isEqualTo("标题");
        assertThat(saved.getContent()).isEqualTo("内容");
        assertThat(saved.getStatus()).isZero();
        assertThat(result).isSameAs(saved);
    }
}
