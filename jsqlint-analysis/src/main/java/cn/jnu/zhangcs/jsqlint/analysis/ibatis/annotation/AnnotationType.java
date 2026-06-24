package cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * MyBatis注解
 *
 * @author zhangcs
 * @since 1.0.0
 */
@Getter
@AllArgsConstructor
public enum AnnotationType {

    UNKNOWN(""),

    MAPPER_SCANS("org.mybatis.spring.annotation.MapperScans"),

    MAPPER_SCAN("org.mybatis.spring.annotation.MapperScan"),

    MAPPER("org.apache.ibatis.annotations.Mapper"),

    SELECT("org.apache.ibatis.annotations.Select"),

    SELECT_PROVIDER("org.apache.ibatis.annotations.SelectProvider"),

    INSERT("org.apache.ibatis.annotations.Insert"),

    INSERT_PROVIDER("org.apache.ibatis.annotations.InsertProvider"),

    DELETE("org.apache.ibatis.annotations.Delete"),

    DELETE_PROVIDER("org.apache.ibatis.annotations.DeleteProvider"),

    UPDATE("org.apache.ibatis.annotations.Update"),

    UPDATE_PROVIDER("org.apache.ibatis.annotations.UpdateProvider"),

    ;

    private final String name;

}
