package org.u80dc.jsqlint.core.spi;

import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.Optional;

/**
 * 已被模块确认为 SQL 入口的方法声明或实际调用位置，不要求此时已能提取 SQL。
 * 只保存签名和位置，不持有方法体、JavaView 或编译输入的生命周期。
 *
 * @author u80dc
 */
public record SqlCallSite(MethodSignature target, Optional<MethodSignature> caller, SourceLocation location) {
    public SqlCallSite {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(location, "location");
    }

    /** 无调用者表示方法声明，例如尚未被调用的 Mapper 接口方法。 */
    public Kind kind() { return caller.isPresent() ? Kind.INVOCATION : Kind.DECLARATION; }

    public static SqlCallSite declaration(JavaSootMethod method) {
        try {
            return new SqlCallSite(method.getSignature(), Optional.empty(),
                    new SourceLocation(new URI("java", method.getDeclClassType().toString(), null),
                            method.getSignature().toString()));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** target 是字节码中的静态调用签名，不等同于运行时动态分派后的实现方法。 */
    public static SqlCallSite invocation(InvocationSite site) {
        return new SqlCallSite(site.invocation().getMethodSignature(),
                Optional.of(site.method().getSignature()), site.location());
    }

    public enum Kind { DECLARATION, INVOCATION }
}
