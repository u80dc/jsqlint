package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.entity.Notification;

import java.util.List;

/**
 * 站内通知服务接口
 * <p>负责通知的查询、标记已读、删除以及创建等能力，是告警触发后通知接收人的落库入口。
 *
 * @author zhangcs
 */
public interface NotificationService {

    /**
     * 查询用户的站内通知列表
     *
     * @param userId 用户ID
     * @return 通知列表
     */
    List<Notification> listByUserId(Long userId);

    /**
     * 根据ID获取通知详情
     *
     * @param id 通知ID
     * @return 通知信息
     */
    Notification getById(Long id);

    /**
     * 将指定通知标记为已读
     *
     * @param id 通知ID
     */
    void markAsRead(Long id);

    /**
     * 将用户全部未读通知标记为已读
     *
     * @param userId 用户ID
     */
    void markAllAsRead(Long userId);

    /**
     * 删除指定用户的某条通知
     *
     * @param id     通知ID
     * @param userId 用户ID
     */
    void deleteByIdAndUserId(Long id, Long userId);

    /**
     * 查询用户未读通知数量
     *
     * @param userId 用户ID
     * @return 未读数量
     */
    int getUnreadCount(Long userId);

    /**
     * 创建一条站内通知
     *
     * @param userId  接收用户ID
     * @param type    通知类型
     * @param title   通知标题
     * @param content 通知内容
     * @return 新建的通知
     */
    Notification create(Long userId, String type, String title, String content);
}
