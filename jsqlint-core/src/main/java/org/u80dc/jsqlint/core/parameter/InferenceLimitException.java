package org.u80dc.jsqlint.core.parameter;

/** 内部资源上限信号，与目标程序本身的运算异常区分，不依赖异常文案。 @author u80dc */
final class InferenceLimitException extends org.u80dc.jsqlint.core.semantics.ValueEvaluationLimitException {
    InferenceLimitException(String message) { super(message); }
}
