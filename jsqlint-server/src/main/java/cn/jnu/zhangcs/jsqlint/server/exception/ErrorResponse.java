package cn.jnu.zhangcs.jsqlint.server.exception;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 错误响应对象
 *
 * @author zhangcs
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    /**
     * 错误码
     */
    private int code;

    /**
     * 错误信息
     */
    private String message;

    private String path;

    private LocalDateTime timestamp;
}