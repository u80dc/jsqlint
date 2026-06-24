package cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * MyBatis注解定义的属性
 *
 * @author zhangcs
 * @since 1.0.0
 */
@Getter
@AllArgsConstructor
public enum AnnotationAttribute {

    VALUE("value"),

    BASE_PACKAGE_CLASSES("basePackageClasses"),

    METHOD("method"),

    TYPE("type"),

    ;

    private final String name;

}
