package org.u80dc.jsqlint.core.spi;

import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;
import sootup.core.types.ClassType;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

/**
 * 一次共享代码扫描发现的调用点。
 *
 * @author u80dc
 */
public record InvocationSite(JavaSootMethod method, Stmt statement, int statementIndex,
                             AbstractInvokeExpr invocation, JavaView view) {
    /**
     * 沿声明类型的接口和父类检查类型关系，包含类型自身。
     * 这不是运行时调用目标推断；缺失的类型定义无法继续向上展开。
     */
    public boolean isDeclaredOnSubtypeOf(String name) {
        Deque<ClassType> types = new ArrayDeque<>();
        Set<ClassType> visited = new HashSet<>();
        types.add(invocation.getMethodSignature().getDeclClassType());
        while (!types.isEmpty()) {
            ClassType type = types.removeFirst();
            if (!visited.add(type)) continue;
            if (type.toString().equals(name)) return true;
            var clazz = view.getClass(type);
            if (clazz.isPresent()) {
                types.addAll(clazz.get().getInterfaces());
                clazz.get().getSuperclass().ifPresent(types::add);
            }
        }
        return false;
    }

    /** 使用类、方法签名和 Jimple 语句索引定位调用，不依赖源码文件是否存在。 */
    public SourceLocation location() {
        try {
            return new SourceLocation(new URI("java", method.getDeclClassType().toString(), null),
                    method.getSignature() + " @" + statementIndex);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** 将指定实参与其使用位置一起交给 core，参数下标从零开始。 */
    public SqlInput.ValueAt argument(int index) {
        return new SqlInput.ValueAt(method, statement, invocation.getArg(index));
    }
}
