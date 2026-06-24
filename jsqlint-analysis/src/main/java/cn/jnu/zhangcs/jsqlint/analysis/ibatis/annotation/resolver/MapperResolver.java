package cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.resolver;

import cn.jnu.zhangcs.jsqlint.analysis.util.JimpleConstantUtils;
import org.jspecify.annotations.NonNull;
import sootup.core.jimple.common.constant.ClassConstant;
import sootup.core.jimple.common.constant.StringConstant;
import sootup.core.model.SootClass;
import sootup.java.bytecode.frontend.conversion.AsmUtil;
import sootup.java.core.AnnotationUsage;
import sootup.java.core.JavaSootClass;
import sootup.java.core.views.JavaView;

import java.util.*;
import java.util.stream.Collectors;

import static cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.AnnotationAttribute.BASE_PACKAGE_CLASSES;
import static cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.AnnotationAttribute.VALUE;
import static cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.AnnotationUtils.*;
import static cn.jnu.zhangcs.jsqlint.analysis.spring.SpringUtils.isRepository;

/**
 * 接口定义注解解析
 *
 * @author zhangcs
 */
public class MapperResolver {

    /**
     * 获取所有Mapper接口类
     *
     * @param view 类视图
     * @return 返回接口类集合
     */
    @NonNull
    public static Set<JavaSootClass> resolve(JavaView view) {
        Set<JavaSootClass> mappers = new HashSet<>();
        Set<String> pkgNames = view.getClasses()
                .filter(SootClass::isApplicationClass)
                .map(c -> {
                    Set<String> set = new HashSet<>();
                    Iterable<AnnotationUsage> annotations = c.getAnnotations();
                    for (AnnotationUsage annotation : annotations) {
                        if (isMapperScans(annotation)) {
                            // 扫描
                            set.addAll(resolveMapperScans(annotation));
                        } else if (isMapperScan(annotation)) {
                            // 扫描路径
                            set.addAll(resolveMapperScan(annotation));
                        } else if (isMapper(annotation)) {
                            // 类直接标注了`@org.apache.ibatis.annotations.Mapper`注解
                            mappers.add(c);
                        } else if (isRepository(annotation)) {
                            // 类标注了`@org.springframework.stereotype.Repository`注解
                            mappers.add(c);
                        }
                    }
                    return set;
                })
                .flatMap(Set::stream)
                .collect(Collectors.toSet());
        mappers.addAll(resolveMapper(view, pkgNames));
        return mappers;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> resolveMapperScan(AnnotationUsage usage) {
        Set<String> packages = new HashSet<>();
        Map<String, Object> values = usage.getValues();
        if (values.isEmpty()) {
            return packages;
        }

        Object basePackages = values.get(VALUE.getName());
        if (JimpleConstantUtils.isListOfStringConstant(basePackages)) {
            List<String> list = ((List<StringConstant>) basePackages).stream()
                    .map(StringConstant::getValue)
                    .toList();
            packages.addAll(list);
        }
        Object classes = values.get(BASE_PACKAGE_CLASSES.getName());
        if (JimpleConstantUtils.isListOfClassConstant(classes)) {
            List<String> list = ((List<ClassConstant>) classes).stream()
                    .map(ClassConstant::getValue)
                    .map(AsmUtil::toQualifiedName)
                    // 去除具体类名，只保留包路径
                    .map(name -> name.substring(0, name.lastIndexOf('.')))
                    .toList();
            packages.addAll(list);
        }
        return packages;
    }

    private static Set<String> resolveMapperScans(AnnotationUsage usage) {
        Set<String> packages = new HashSet<>();
        Map<String, Object> values = usage.getValues();
        Object value = values.get(VALUE.getName());
        if (!(value instanceof List<?> mapperScans)) {
            return Collections.emptySet();
        }

        for (Object mapperScan : mapperScans) {
            if (!(mapperScan instanceof AnnotationUsage au)) {
                continue;
            }
            if (isMapperScan(au)) {
                packages.addAll(resolveMapperScan(au));
            }
        }
        return packages;
    }

    private static List<JavaSootClass> resolveMapper(JavaView view, Collection<String> packages) {
        if (packages.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> pkNames = packages.stream()
                .map(p -> p.endsWith(".") ? p : p + ".")
                .collect(Collectors.toSet());
        return view.getClasses()
                .filter(SootClass::isApplicationClass)
                .filter(SootClass::isInterface)
                .map(clazz -> {
                    String name = clazz.getName();
                    for (String pkName : pkNames) {
                        if (name.startsWith(pkName)) {
                            return clazz;
                        }
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

}
