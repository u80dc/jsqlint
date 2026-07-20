package cn.jnu.zhangcs.jsqlint.server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 角色枚举类
 *
 * @author zhangcs
 */
@Getter
@RequiredArgsConstructor
public enum Role {

    ADMIN("ROLE_ADMIN", "系统管理员"),

    USER("ROLE_USER", "普通用户");

    /**
     * 角色
     */
    private final String value;

    /**
     * 描述信息
     */
    private final String desc;

    public static Role from(String value) {
        for (Role role : values()) {
            if (role.value.equals(value)) {
                return role;
            }
        }
        return USER;
    }
}