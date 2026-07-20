package cn.jnu.zhangcs.jsqlint.server.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 系统操作类型枚举
 *
 * @author zhangcs
 */
@Getter
@AllArgsConstructor
public enum SystemOperationType {

    // 用户管理
    USER_CREATE("USER_CREATE", "创建用户"),
    USER_UPDATE("USER_UPDATE", "更新用户"),
    USER_DELETE("USER_DELETE", "删除用户"),
    USER_ENABLE("USER_ENABLE", "启用用户"),
    USER_DISABLE("USER_DISABLE", "禁用用户"),
    USER_RESET_PASSWORD("USER_RESET_PASSWORD", "重置密码"),

    // 凭证管理
    CREDENTIAL_CREATE("CREDENTIAL_CREATE", "创建凭证"),
    CREDENTIAL_UPDATE("CREDENTIAL_UPDATE", "更新凭证"),
    CREDENTIAL_DELETE("CREDENTIAL_DELETE", "删除凭证"),
    CREDENTIAL_ENABLE("CREDENTIAL_ENABLE", "启用凭证"),
    CREDENTIAL_DISABLE("CREDENTIAL_DISABLE", "禁用凭证"),

    // 审核规则管理
    RULE_CREATE("RULE_CREATE", "创建审核规则"),
    RULE_UPDATE("RULE_UPDATE", "更新审核规则"),
    RULE_DELETE("RULE_DELETE", "删除审核规则"),
    RULE_ENABLE("RULE_ENABLE", "启用审核规则"),
    RULE_DISABLE("RULE_DISABLE", "禁用审核规则"),
    RULE_VERSION_CREATE("RULE_VERSION_CREATE", "创建规则版本"),

    // 通知设置
    NOTIFICATION_CREATE("NOTIFICATION_CREATE", "创建通知设置"),
    NOTIFICATION_UPDATE("NOTIFICATION_UPDATE", "更新通知设置"),
    NOTIFICATION_DELETE("NOTIFICATION_DELETE", "删除通知设置"),

    // Maven 仓库管理
    REPOSITORY_CREATE("REPOSITORY_CREATE", "创建仓库"),
    REPOSITORY_UPDATE("REPOSITORY_UPDATE", "更新仓库"),
    REPOSITORY_DELETE("REPOSITORY_DELETE", "删除仓库"),

    // 用户组管理
    USER_GROUP_CREATE("USER_GROUP_CREATE", "创建用户组"),
    USER_GROUP_UPDATE("USER_GROUP_UPDATE", "更新用户组"),
    USER_GROUP_DELETE("USER_GROUP_DELETE", "删除用户组"),
    USER_GROUP_MEMBER_ADD("USER_GROUP_MEMBER_ADD", "添加用户组成员"),
    USER_GROUP_MEMBER_REMOVE("USER_GROUP_MEMBER_REMOVE", "移除用户组成员");

    private final String value;
    private final String desc;

    public static SystemOperationType from(String value) {
        if (value == null) {
            return null;
        }
        for (SystemOperationType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return null;
    }
}
