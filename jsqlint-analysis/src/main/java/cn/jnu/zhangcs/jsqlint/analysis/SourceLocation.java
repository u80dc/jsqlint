package cn.jnu.zhangcs.jsqlint.analysis;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootUtils;

import java.util.List;

/**
 * SQL 定义来源位置信息
 */
@Getter
@RequiredArgsConstructor
public class SourceLocation {

    /**
     * 类全名
     */
    private final String className;

    /**
     * 方法名
     */
    private final String methodName;

    /**
     * 返回类型
     */
    private final String returnType;

    /**
     * 参数类型
     */
    private final List<String> parameterTypes;

    /**
     * 源文件
     */
    private final String sourceFile;

    /**
     * 参考行号
     */
    private final int lineNumber;

    public static SourceLocation of(String className, String methodName, String returnType, List<String> parameterTypes, String sourceFile) {
        return new SourceLocation(className, methodName, returnType, parameterTypes, sourceFile, 0);
    }

    public static SourceLocation of(String className, String methodName, String returnType, List<String> parameterTypes, String sourceFile, int lineNumber) {
        return new SourceLocation(className, methodName, returnType, parameterTypes, sourceFile, lineNumber);
    }

    public String getFullSignature() {
        StringBuilder sb = new StringBuilder();
        sb.append(className).append(".").append(methodName).append("(");
        if (parameterTypes != null && !parameterTypes.isEmpty()) {
            sb.append(String.join(", ", parameterTypes));
        }
        sb.append(")");
        return sb.toString();
    }

    public MethodSignature getMethod() {
        return JavaSootUtils.getMethodSignature(
                className,
                methodName,
                returnType,
                parameterTypes.toArray(String[]::new)
        );
    }

}