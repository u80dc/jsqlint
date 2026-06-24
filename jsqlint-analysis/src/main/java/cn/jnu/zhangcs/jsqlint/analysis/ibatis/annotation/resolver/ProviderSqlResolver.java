package cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.resolver;

import cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.ProviderSqlAnalyzer;
import cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.ProviderSqlStatement;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import sootup.core.jimple.common.constant.ClassConstant;
import sootup.core.jimple.common.constant.StringConstant;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.Type;
import sootup.java.core.AnnotationUsage;
import sootup.java.core.types.JavaClassType;
import sootup.java.core.views.JavaView;

import java.util.List;
import java.util.Map;

import static cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.AnnotationAttribute.METHOD;
import static cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.AnnotationAttribute.TYPE;


/**
 * 解析 Provider 注解
 *
 * @author zhangcs
 */
@Slf4j
public class ProviderSqlResolver {

    /**
     * 解析 Provider 方法并返回动态 SQL 文本
     *
     * @param view           JavaView
     * @param methodName     Mapper 接口方法名
     * @param usage          @SelectProvider/@InsertProvider 等注解
     * @param parameterTypes 参数类型列表
     * @return 动态 SQL 文本，如 "<script>SELECT * FROM user WHERE id = #{id}</script>"，或 null
     */
    @Nullable
    public static ProviderSqlStatement resolve(JavaView view, String methodName, AnnotationUsage usage, List<Type> parameterTypes) {
        Map<String, Object> values = usage.getValues();
        try {
            // 获取Provider类
            ClassConstant type = (ClassConstant) values.get(TYPE.getName());
            String className = getClassName(type);
            JavaClassType classType = view.getIdentifierFactory().getClassType(className);

            // 获取对应方法，未指定时默认为mapper接口方法同名
            StringConstant sc = (StringConstant) values.get(METHOD.getName());
            String providerMethodName = sc.getValue().isEmpty() ? methodName : sc.getValue();
            log.debug("provider: {}#{}", className, providerMethodName);

            // 获取provider中的对应方法
            MethodSignature method = view.getIdentifierFactory()
                    .getMethodSignature(
                            classType,
                            providerMethodName,
                            view.getIdentifierFactory().getClassType("java.lang.String"),
                            parameterTypes
                    );

            ProviderSqlAnalyzer analyzer = new ProviderSqlAnalyzer(view);
            return analyzer.analyze(method);
        } catch (Exception e) {
            // 解析异常，可能是代码定义错误
            log.warn("Failed to resolve provider method: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * 获取注解配置的类名
     *
     * @param constant 类常量
     * @return 返回类名
     */
    private static String getClassName(ClassConstant constant) {
        String value = constant.getValue();
        // 去除末尾结束符
        value = value.substring(0, value.length() - 1);
        // Provider注解的type属性只支持类类型，即固定'L'开头
        if (value.startsWith("L")) {
            value = value.substring(1);
            value = value.replace("/", ".");
            return value;
        }
        return null;
    }

}
