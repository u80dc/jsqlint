package cn.jnu.zhangcs.jsqlint.server.common.annotation;

import java.lang.annotation.*;

/**
 * 系统操作日志注解
 * 用于标记需要记录系统日志的方法
 *
 * @author zhangcs
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SystemLog {

    /**
     * 操作类型（必填）
     * 例如: "USER_CREATE", "CREDENTIAL_UPDATE"
     */
    String operationType();

    /**
     * 操作信息文本（必填）
     * 支持SpEL表达式，可以引用方法参数和返回值
     * 例如: "创建用户: #{#user.username} (账号: #{#user.account})"
     * 例如: "删除用户ID: #{#id}"
     * 例如: "创建用户成功，用户ID: #{#result.id}"
     */
    String message();

    /**
     * 关联资源ID（可选）
     * 支持SpEL表达式
     * 例如: "#id" (引用方法参数id)
     * 例如: "#user.id" (引用方法参数user的id属性)
     * 例如: "#result.id" (引用方法返回值的id属性)
     */
    String relatedId() default "";

    /**
     * 是否记录方法返回值
     * 如果为true，可以在message和relatedId中使用 #result 引用返回值
     */
    boolean includeResult() default false;
}
