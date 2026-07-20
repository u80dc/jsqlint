package cn.jnu.zhangcs.jsqlint.server.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 业务错误状态码
 *
 * @author zhangcs
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    NOT_FOUND("NOT_FOUND", "资源不存在"),

    BAD_REQUEST("BAD_REQUEST", "请求参数错误"),

    UNAUTHORIZED("UNAUTHORIZED", "未授权"),

    FORBIDDEN("FORBIDDEN", "无权限"),

    INTERNAL_ERROR("INTERNAL_ERROR", "服务器内部错误");

    private final String code;

    private final String message;
}