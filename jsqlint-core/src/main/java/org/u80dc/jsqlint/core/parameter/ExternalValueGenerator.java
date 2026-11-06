package org.u80dc.jsqlint.core.parameter;

import org.u80dc.jsqlint.core.node.SqlValue;

/** 根据外部输入的声明类型产生样例；不支持的类型应返回 Unknown。 @author u80dc */
@FunctionalInterface
public interface ExternalValueGenerator {
    SqlValue generate(String type, String name);
}
