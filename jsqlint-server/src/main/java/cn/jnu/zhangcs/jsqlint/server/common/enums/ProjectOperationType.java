package cn.jnu.zhangcs.jsqlint.server.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 项目操作类型枚举
 *
 * @author zhangcs
 */
@Getter
@AllArgsConstructor
public enum ProjectOperationType {

    // 项目管理
    PROJECT_CREATE("PROJECT_CREATE", "创建项目"),
    PROJECT_UPDATE("PROJECT_UPDATE", "更新项目"),
    PROJECT_DELETE("PROJECT_DELETE", "删除项目"),
    PROJECT_ARCHIVE("PROJECT_ARCHIVE", "归档项目"),
    PROJECT_RESTORE("PROJECT_RESTORE", "恢复项目"),

    // 项目成员管理
    MEMBER_ADD("MEMBER_ADD", "添加成员"),
    MEMBER_ADD_BY_GROUP("MEMBER_ADD_BY_GROUP", "添加成员"),
    MEMBER_REMOVE("MEMBER_REMOVE", "移除成员"),
    MEMBER_ROLE_UPDATE("MEMBER_ROLE_UPDATE", "更新成员角色"),

    // 项目审核规则管理
    PROJECT_RULE_ENABLE("PROJECT_RULE_ENABLE", "启用项目审核规则"),
    PROJECT_RULE_DISABLE("PROJECT_RULE_DISABLE", "禁用项目审核规则"),
    PROJECT_RULE_ADD("PROJECT_RULE_ADD", "添加项目审核规则"),
    PROJECT_RULE_UPDATE("PROJECT_RULE_UPDATE", "更新项目审核规则"),
    PROJECT_RULE_REMOVE("PROJECT_RULE_REMOVE", "移除项目审核规则"),

    // 项目凭证管理
    PROJECT_CREDENTIAL_BIND("PROJECT_CREDENTIAL_BIND", "绑定凭证"),
    PROJECT_CREDENTIAL_UNBIND("PROJECT_CREDENTIAL_UNBIND", "解绑凭证"),
    PROJECT_CREDENTIAL_UPDATE("PROJECT_CREDENTIAL_UPDATE", "更新凭证"),

    // 项目版本管理
    VERSION_CREATE("VERSION_CREATE", "创建版本"),
    VERSION_ANALYZE("VERSION_ANALYZE", "分析版本"),
    VERSION_DELETE("VERSION_DELETE", "删除版本"),

    // 项目工作区管理
    WORKSPACE_ADD("WORKSPACE_ADD", "添加到工作区"),
    WORKSPACE_REMOVE("WORKSPACE_REMOVE", "从工作区移除"),

    // 项目配置管理
    CONFIG_UPDATE("CONFIG_UPDATE", "更新项目配置"),
    MAVEN_REPO_UPDATE("MAVEN_REPO_UPDATE", "更新Maven仓库配置");

    private final String value;
    private final String desc;

    public static ProjectOperationType from(String value) {
        if (value == null) {
            return null;
        }
        for (ProjectOperationType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return null;
    }
}
