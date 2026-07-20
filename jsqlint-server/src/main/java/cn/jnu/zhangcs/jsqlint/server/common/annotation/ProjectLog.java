package cn.jnu.zhangcs.jsqlint.server.common.annotation;

import java.lang.annotation.*;

/**
 * 项目操作日志注解
 * 用于标记需要记录项目日志的方法
 *
 * @author zhangcs
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ProjectLog {

    /**
     * 项目ID（必填）
     * 支持SpEL表达式
     * 例如: "#projectId" (引用方法参数projectId)
     * 例如: "#project.id" (引用方法参数project的id属性)
     */
    String projectId();

    /**
     * 操作类型（必填）
     * 例如: "PROJECT_CREATE", "MEMBER_ADD"
     */
    String operationType();

    /**
     * 操作信息文本（必填）
     * 支持SpEL表达式，可以引用方法参数和返回值
     * 例如: "创建项目: #{#project.name}"
     * 例如: "添加成员: #{#userId}"
     * 例如: "创建项目成功，项目ID: #{#result.id}"
     */
    String message();

    /**
     * 关联资源ID（可选）
     * 支持SpEL表达式
     * 例如: "#memberId" (引用方法参数memberId)
     * 例如: "#result.id" (引用方法返回值的id属性)
     */
    String relatedId() default "";

    /**
     * 是否记录方法返回值
     * 如果为true，可以在projectId、message和relatedId中使用 #result 引用返回值
     */
    boolean includeResult() default false;
}
