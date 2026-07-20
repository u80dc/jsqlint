package cn.jnu.zhangcs.jsqlint.server.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 可见性枚举类，用于标识资源的可见状态
 *
 * @author zhangcs
 */
@Getter
@AllArgsConstructor
public enum Visibility {

    PUBLIC(1, "公开"),

    PRIVATE(0, "私人");

    private final int value;

    private final String desc;

    public static Visibility from(Integer value) {
        if (value == null) {
            return PRIVATE;
        }
        for (Visibility v : values()) {
            if (v.value == value) {
                return v;
            }
        }
        return PRIVATE;
    }

}