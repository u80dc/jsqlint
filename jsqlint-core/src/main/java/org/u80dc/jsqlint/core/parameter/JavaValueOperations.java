package org.u80dc.jsqlint.core.parameter;

import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.signatures.MethodSignature;
import java.util.List;

/**
 * Java 纯值语义的旧包入口；实现位于 semantics 包，供来源追踪和各类求值共同使用。
 * @deprecated 使用 {@link org.u80dc.jsqlint.core.semantics.JavaValueOperations}。
 * @author u80dc
 */
@Deprecated
public final class JavaValueOperations {
    private JavaValueOperations() {}
    public static boolean scalarStringConcat(AbstractInvokeExpr invocation) {
        return org.u80dc.jsqlint.core.semantics.JavaValueOperations.scalarStringConcat(invocation);
    }
    public static boolean supports(MethodSignature signature) {
        return org.u80dc.jsqlint.core.semantics.JavaValueOperations.supports(signature);
    }
    static Object call(MethodSignature method, List<Object> operands, int maxLength) {
        return org.u80dc.jsqlint.core.semantics.JavaValueOperations.call(method, operands, maxLength);
    }
    static Object parse(String text, String type) {
        return org.u80dc.jsqlint.core.semantics.JavaValueOperations.parse(text, type);
    }
    static Object cast(Object value, String type) {
        return org.u80dc.jsqlint.core.semantics.JavaValueOperations.cast(value, type);
    }
    static String stringify(Object value, String declaredType) {
        return org.u80dc.jsqlint.core.semantics.JavaValueOperations.stringify(value, declaredType);
    }
    static Object negate(Object value, String type) {
        return org.u80dc.jsqlint.core.semantics.JavaValueOperations.negate(value, type);
    }
    static Object binary(String operator, Object left, Object right, String type) {
        return org.u80dc.jsqlint.core.semantics.JavaValueOperations.binary(operator, left, right, type);
    }
    static String replace(String value, String target, String replacement, int maxLength) {
        return org.u80dc.jsqlint.core.semantics.JavaValueOperations.replace(value, target, replacement, maxLength);
    }
}
