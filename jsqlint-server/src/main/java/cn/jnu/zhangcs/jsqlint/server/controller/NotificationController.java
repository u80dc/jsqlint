package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.entity.Notification;
import cn.jnu.zhangcs.jsqlint.server.model.vo.NotificationVO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "通知管理", description = "用户通知的查询、标记已读等操作")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "查询通知列表", description = "查询当前用户的所有通知列表")
    @GetMapping
    public ResponseEntity<List<NotificationVO>> listNotifications(@Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        var notifications = notificationService.listByUserId(loginUser.getUserId());
        return ResponseEntity.ok(notifications.stream()
                .map(this::toVO)
                .toList());
    }

    @Operation(summary = "获取未读通知数量", description = "获取当前用户的未读通知数量")
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Object>> getUnreadCount(@Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        int count = notificationService.getUnreadCount(loginUser.getUserId());
        return ResponseEntity.ok(Map.of("count", count));
    }

    @Operation(summary = "标记通知为已读", description = "将指定ID的通知标记为已读状态")
    @PutMapping("/{id}/read")
    public ResponseEntity<Map<String, Object>> markAsRead(
            @Parameter(name = "id", description = "通知ID", example = "1") @PathVariable("id") Long id) {
        notificationService.markAsRead(id);
        return ResponseEntity.ok(Map.of("message", "标记已读成功"));
    }

    @Operation(summary = "标记所有通知为已读", description = "将当前用户的所有通知标记为已读状态")
    @PutMapping("/read-all")
    public ResponseEntity<Map<String, Object>> markAllAsRead(@Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        notificationService.markAllAsRead(loginUser.getUserId());
        return ResponseEntity.ok(Map.of("message", "全部标记已读成功"));
    }

    @Operation(summary = "删除通知", description = "删除当前用户的指定通知")
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteNotification(
            @Parameter(name = "id", description = "通知ID", example = "1") @PathVariable("id") Long id,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        notificationService.deleteByIdAndUserId(id, loginUser.getUserId());
        return ResponseEntity.ok(Map.of("message", "通知删除成功"));
    }

    private NotificationVO toVO(Notification notification) {
        return NotificationVO.builder()
                .id(notification.getId())
                .type(notification.getType())
                .typeDescription(getTypeDescription(notification.getType()))
                .title(notification.getTitle())
                .content(notification.getContent())
                .status(notification.getStatus())
                .readTime(notification.getReadTime())
                .createTime(notification.getCreateTime())
                .build();
    }

    private String getTypeDescription(String type) {
        return switch (type) {
            case "ANALYSIS_COMPLETE" -> "分析完成";
            case "ANALYSIS_FAILED" -> "分析失败";
            case "TASK_COMPLETE" -> "任务完成";
            case "TASK_FAILED" -> "任务失败";
            case "ALERT_TRIGGERED" -> "告警触发";
            default -> "系统通知";
        };
    }
}
