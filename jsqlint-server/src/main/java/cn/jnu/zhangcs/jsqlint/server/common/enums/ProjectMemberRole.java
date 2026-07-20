package cn.jnu.zhangcs.jsqlint.server.common.enums;

import lombok.Getter;

/**
 * 项目成员角色枚举
 *
 * @author zhangcs
 */
@Getter
public enum ProjectMemberRole {

    NORMAL(0, "普通用户"),

    ADMIN(1, "项目管理员");

    /**
     * 角色码
     */
    private final int code;

    /**
     * 描述信息
     */
    private final String desc;

    ProjectMemberRole(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static ProjectMemberRole from(int value) {
        for (ProjectMemberRole role : values()) {
            if (role.code == value) {
                return role;
            }
        }
        return NORMAL;
    }

    public static ProjectMemberRole from(Integer value) {
        if (value == null) {
            return NORMAL;
        }
        return from((int) value);
    }

}