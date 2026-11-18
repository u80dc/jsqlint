package org.u80dc.jsqlint.core.semantics;

/** 纯值求值的资源上限信号，与目标 Java 运算本身的异常区分。 @author u80dc */
public class ValueEvaluationLimitException extends IllegalArgumentException {
    public ValueEvaluationLimitException(String message) { super(message); }
}
