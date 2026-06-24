package cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation;

import sootup.core.types.ClassType;
import sootup.java.core.AnnotationUsage;

import javax.annotation.Nonnull;

import static cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.AnnotationType.*;

/**
 * 注解工具类
 *
 * @author zhangcs
 * @since 1.0.0
 */
public class AnnotationUtils {

    /**
     * 判断是否相同注解
     *
     * @param usage  注解
     * @param target 注解
     * @return 相同时返回true，否则返回false
     */
    public static boolean is(@Nonnull AnnotationUsage usage, @Nonnull AnnotationType target) {
        ClassType type = usage.getAnnotation();
        String qualifiedName = type.getFullyQualifiedName();
        return AnnotationUtils.is(qualifiedName, target);
    }

    /**
     * 判断是否相同注解
     *
     * @param annotation 注解全路径名称
     * @param target     注解
     * @return 相同时返回true，否则返回false
     */
    public static boolean is(String annotation, @Nonnull AnnotationType target) {
        return of(annotation) == target;
    }

    /**
     * 根据全路径名获取对应的注解对象
     *
     * @param annotation 全路径名
     * @return 返回对象
     */
    @Nonnull
    public static AnnotationType of(String annotation) {
        if (annotation == null || annotation.trim().isEmpty()) {
            return AnnotationType.UNKNOWN;
        }

        AnnotationType[] values = AnnotationType.values();
        for (AnnotationType value : values) {
            if (value.getName().equals(annotation)) {
                return value;
            }
        }
        return AnnotationType.UNKNOWN;
    }

    public static boolean isMapper(@Nonnull AnnotationUsage usage) {
        return is(usage, MAPPER);
    }

    public static boolean isMapperScan(@Nonnull AnnotationUsage usage) {
        return is(usage, MAPPER_SCAN);
    }

    public static boolean isMapperScans(@Nonnull AnnotationUsage usage) {
        return is(usage, MAPPER_SCANS);
    }

    public static boolean isSelect(@Nonnull AnnotationUsage usage) {
        return is(usage, SELECT);
    }

    public static boolean isSelectProvider(@Nonnull AnnotationUsage usage) {
        return is(usage, SELECT_PROVIDER);
    }

    public static boolean isInsert(@Nonnull AnnotationUsage usage) {
        return is(usage, INSERT);
    }

    public static boolean isInsertProvider(@Nonnull AnnotationUsage usage) {
        return is(usage, INSERT_PROVIDER);
    }

    public static boolean isUpdate(@Nonnull AnnotationUsage usage) {
        return is(usage, UPDATE);
    }

    public static boolean isUpdateProvider(@Nonnull AnnotationUsage usage) {
        return is(usage, UPDATE_PROVIDER);
    }

    public static boolean isDelete(@Nonnull AnnotationUsage usage) {
        return is(usage, DELETE);
    }

    public static boolean isDeleteProvider(@Nonnull AnnotationUsage usage) {
        return is(usage, DELETE_PROVIDER);
    }

    /**
     * 是否映射增删改查操作注解
     *
     * @param usage 注解
     * @return 属于映射操作时返回true，否则返回false
     */
    public static boolean isMapping(@Nonnull AnnotationUsage usage) {
        return isInsert(usage)
                || isDelete(usage)
                || isUpdate(usage)
                || isSelect(usage)
                || isInsertProvider(usage)
                || isDeleteProvider(usage)
                || isUpdateProvider(usage)
                || isSelectProvider(usage);
    }

    /**
     * 是否映射增删改查操作注解
     *
     * @param usage 注解
     * @return 属于Provider（提供者）方式实现时返回true，否则返回false
     */
    public static boolean isMappingProvider(@Nonnull AnnotationUsage usage) {
        return isInsertProvider(usage)
                || isDeleteProvider(usage)
                || isUpdateProvider(usage)
                || isSelectProvider(usage);
    }

}
