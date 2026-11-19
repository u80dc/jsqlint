package org.u80dc.jsqlint.core.semantics;

import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.expr.JStaticInvokeExpr;
import java.util.Set;

/** 已知 Java 方法对引用实参的效果；值转换和正常完成仍由对应语义模型负责。 */
public final class JavaMethodEffects {
    private JavaMethodEffects() {}

    public enum ReferenceEffect { READ_ONLY, UNKNOWN }

    private static final Set<String> READ_ONLY_REFERENCES = Set.of(
            "<java.util.Objects: java.lang.Object requireNonNull(java.lang.Object)>",
            "<java.util.Objects: java.lang.Object requireNonNull(java.lang.Object,java.lang.String)>");

    /** Supplier 重载可能执行用户回调；只有完整匹配的已知静态签名可以免除未知堆效果。 */
    public static ReferenceEffect references(AbstractInvokeExpr invocation) {
        return invocation instanceof JStaticInvokeExpr
                && READ_ONLY_REFERENCES.contains(invocation.getMethodSignature().toString())
                ? ReferenceEffect.READ_ONLY : ReferenceEffect.UNKNOWN;
    }
}
