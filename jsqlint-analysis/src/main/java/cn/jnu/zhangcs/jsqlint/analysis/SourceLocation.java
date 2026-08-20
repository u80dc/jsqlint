package cn.jnu.zhangcs.jsqlint.analysis;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootUtils;

import java.util.List;

/**
 * SQL 定义来源位置信息。
 *
 * <p>记录一条 SQL 语句在源代码中的定位：所属类与方法签名、源文件路径及参考行号，
 * 供结果展示与二次分析使用。</p>
 *
 * @author zhangcs
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

    /**
     * 创建不带行号的位置信息。
     *
     * @param className      类全名
     * @param methodName     方法名
     * @param returnType     返回类型
     * @param parameterTypes 参数类型列表
     * @param sourceFile     源文件路径
     * @return 位置信息，行号为 0
     */
    public static SourceLocation of(String className, String methodName, String returnType, List<String> parameterTypes, String sourceFile) {
        return new SourceLocation(className, methodName, returnType, parameterTypes, sourceFile, 0);
    }

    /**
     * 创建完整的位置信息。
     *
     * @param className      类全名
     * @param methodName     方法名
     * @param returnType     返回类型
     * @param parameterTypes 参数类型列表
     * @param sourceFile     源文件路径
     * @param lineNumber     参考行号
     * @return 位置信息
     */
    public static SourceLocation of(String className, String methodName, String returnType, List<String> parameterTypes, String sourceFile, int lineNumber) {
        return new SourceLocation(className, methodName, returnType, parameterTypes, sourceFile, lineNumber);
    }

    /**
     * 获取可读的完整方法签名，格式为 {@code 类名.方法名(参数类型, ...)}。
     *
     * @return 完整方法签名
     */
    public String getFullSignature() {
        StringBuilder sb = new StringBuilder();
        sb.append(className).append(".").append(methodName).append("(");
        if (parameterTypes != null && !parameterTypes.isEmpty()) {
            sb.append(String.join(", ", parameterTypes));
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * 转换为 SootUp 的方法签名对象。
     *
     * @return 方法签名
     */
    public MethodSignature getMethod() {
        return JavaSootUtils.getMethodSignature(
                className,
                methodName,
                returnType,
                parameterTypes.toArray(String[]::new)
        );
    }

}