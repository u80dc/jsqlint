package org.u80dc.jsqlint.commons.location;

/** 项目构建策略，与源码/字节码及构建工具的输入类型识别独立。 */
public enum BuildPolicy {
    /** 每次构建，保持原默认行为。 */
    ALWAYS,
    /** 只读已有编译快照；调用方选择其版本，不要求当前源码最新。 */
    REUSE,
    /** 校验已记录构建输入，失效时重新构建并发布快照。 */
    AUTO
}
