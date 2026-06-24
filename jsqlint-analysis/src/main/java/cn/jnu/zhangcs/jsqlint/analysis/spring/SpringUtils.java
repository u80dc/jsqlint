package cn.jnu.zhangcs.jsqlint.analysis.spring;

import org.jspecify.annotations.NonNull;
import sootup.core.model.SootClass;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.ClassType;
import sootup.java.core.AnnotationUsage;
import sootup.java.core.JavaSootClass;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.JavaSootUtils;
import sootup.java.core.views.JavaView;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spring相关框架工具类
 *
 * @author zhangcs
 */
public class SpringUtils {

    public static boolean isConfiguration(JavaSootClass clazz) {
        if (clazz.isLibraryClass()) {
            return false;
        }
        return JavaSootUtils.hasAnnotation(clazz, SpringConstant.CONFIGURATION_ANNOTATIONS);
    }

    /**
     * 是否用于定义声明Bean的方法
     *
     * @param method 方法
     * @return 满足判断时返回true
     */
    public static boolean isReturnBean(JavaSootMethod method) {
        return JavaSootUtils.hasAnnotation(method, Collections.singleton(SpringConstant.BEAN_ANNOTATION));
    }

    /**
     * 类是否已经被声明为Bean
     *
     * @param clazz 类
     * @return 是Bean时返回true
     */
    public static boolean isBeanClass(JavaSootClass clazz) {
        return JavaSootUtils.hasAnnotation(clazz, SpringConstant.BEAN_TYPE_ANNOTATIONS);
    }

    /**
     * 判断是否启动类，即 @SpringBootApplication 注解的类
     *
     * @param clazz 类
     * @return 指定类是启动类时返回true，否则返回false
     */
    public static boolean isBootClass(JavaSootClass clazz) {
        if (clazz.isLibraryClass()) {
            return false;
        }
        return JavaSootUtils.hasAnnotation(clazz, Collections.singleton(SpringConstant.SPRING_BOOT_ANNOTATIONS));
    }

    /**
     * 判断是否控制器类，即@Controller、@RestController注解的类
     *
     * @param clazz 类对象
     * @return 指定类是控制器时返回true
     */
    public static boolean isControllerClass(JavaSootClass clazz) {
        return JavaSootUtils.hasAnnotation(clazz, SpringConstant.CONTROLLER_ANNOTATIONS);
    }

    public static boolean isMapping(JavaSootMethod method) {
        return JavaSootUtils.hasAnnotation(method, SpringConstant.MAPPING_ANNOTATIONS);
    }

    /**
     * 获取请求控制器方法
     *
     * @return 返回方法列表
     */
    @NonNull
    public static Set<JavaSootClass> findController(JavaView view) {
        return view.getClasses()
                .filter(SootClass::isApplicationClass)
                .filter(SpringUtils::isControllerClass)
                .collect(Collectors.toSet());
    }

    /**
     * 获取请求控制器方法
     *
     * @return 返回方法列表
     */
    @NonNull
    public static Set<MethodSignature> findControllerMethodSignatures(JavaView view) {
        return findControllerMethods(view).stream()
                .map(JavaSootMethod::getSignature)
                .collect(Collectors.toSet());
    }

    @NonNull
    public static Set<JavaSootMethod> findControllerMethods(JavaView view) {
        return SpringUtils.findController(view)
                .stream()
                .map(JavaSootClass::getMethods)
                .flatMap(Set::stream)
                .filter(SpringUtils::isMapping)
                .collect(Collectors.toSet());
    }

    /**
     * 判断注解是否为数据访问层注解
     *
     * @param annotation 注解
     * @return 注解为数据访问层时返回true，否则返回false
     */
    public static boolean isRepository(AnnotationUsage annotation) {
        if (annotation == null) {
            return false;
        }
        ClassType type = annotation.getAnnotation();
        String qualifiedName = type.getFullyQualifiedName();
        return SpringConstant.REPOSITORY_ANNOTATION.equals(qualifiedName);
    }

}
