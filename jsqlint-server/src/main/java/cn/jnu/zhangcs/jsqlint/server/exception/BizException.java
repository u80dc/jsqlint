package cn.jnu.zhangcs.jsqlint.server.exception;

/**
 * 业务异常类
 *
 * @author zhangcs
 */
public class BizException extends RuntimeException {

    public BizException(String message) {
        super(message);
    }

    public BizException(String message, Throwable cause) {
        super(message, cause);
    }

}