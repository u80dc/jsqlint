package org.u80dc.jsqlint.core.dataflow;

/** 由适配层选择对象属性的实际读取语义。 */
public enum PropertyAccess {
    /** 公开 JavaBean getter 优先；没有 getter 时读取实例字段，兼容原入口。 */
    JAVA_BEAN,
    /** 只读取实例字段，适用于字段访问的持久化映射。 */
    FIELD,
    /** 只读取同名的公开无参方法，例如 record accessor；没有方法时保持未知。 */
    ACCESSOR
}
