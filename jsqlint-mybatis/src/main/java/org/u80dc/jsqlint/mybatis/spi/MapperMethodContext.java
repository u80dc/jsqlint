package org.u80dc.jsqlint.mybatis.spi;

import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;
import java.util.Objects;

/** 请求签名保留具体 Mapper 类型，method 保留实际声明（可能来自泛型父接口）。 */
public record MapperMethodContext(MethodSignature signature, JavaSootMethod method, JavaView view) {
    public MapperMethodContext {
        Objects.requireNonNull(signature); Objects.requireNonNull(method); Objects.requireNonNull(view);
    }
}
