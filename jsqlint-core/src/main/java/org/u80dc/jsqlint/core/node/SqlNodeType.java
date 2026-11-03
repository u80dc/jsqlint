package org.u80dc.jsqlint.core.node;

/**
 * SqlNode 类型枚举
 *
 * @author u80dc
 */
public enum SqlNodeType {
    MIXED,
    STATIC_TEXT,
    TEXT,
    VALUE,
    IF,
    FOREACH,
    CHOOSE,
    TRIM,
    SET,
    WHERE,
    BIND
}
