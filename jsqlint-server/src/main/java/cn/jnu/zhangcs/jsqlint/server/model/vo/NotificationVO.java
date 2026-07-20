package cn.jnu.zhangcs.jsqlint.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationVO {

    private Long id;

    private String type;

    private String typeDescription;

    private String title;

    private String content;

    private Integer status;

    private LocalDateTime readTime;

    private LocalDateTime createTime;
}