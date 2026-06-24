package cn.jnu.zhangcs.jsqlint.analysis.node;

/**
 * SqlNode 类型枚举
 */
public enum SqlNodeType {
    MIXED,
    STATIC_TEXT,
    TEXT,
    IF,
    FOREACH,
    CHOOSE,
    WHEN,
    OTHERWISE,
    TRIM,
    SET,
    WHERE,
    VAR_DECL,
    BIND
}