package org.u80dc.jsqlint.samples.springmvcjdbc.service;

/**
 * 用户不存在时的业务异常。
 *
 * @author u80dc
 */
public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(long id) {
        super("用户不存在：" + id);
    }
}
