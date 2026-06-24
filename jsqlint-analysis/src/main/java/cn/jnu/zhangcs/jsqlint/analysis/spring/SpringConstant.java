package cn.jnu.zhangcs.jsqlint.analysis.spring;

import java.util.HashSet;
import java.util.Set;

/**
 * Spring相关常量类
 *
 * @author zhangcs
 */
public class SpringConstant {

    /**
     * 引导启动类注解
     */
    public static final String SPRING_BOOT_ANNOTATIONS = "org.springframework.boot.autoconfigure.SpringBootApplication";

    /**
     * 注入注解集合
     */
    public static final Set<String> INJECT_ANNOTATIONS = new HashSet<>() {
        {
            add("org.springframework.beans.factory.annotation.Autowired");
            add("javax.annotation.Resource");
        }
    };

    /**
     * 配置类注解
     */
    public static final Set<String> CONFIGURATION_ANNOTATIONS = new HashSet<>() {
        {
            add("org.springframework.context.annotation.Configuration");
            add("org.springframework.boot.autoconfigure.AutoConfiguration");
        }
    };


    /**
     * 请求控制器类注解
     */
    public static final Set<String> CONTROLLER_ANNOTATIONS = new HashSet<>() {
        {
            add("org.springframework.web.bind.annotation.RestController");
            add("org.springframework.stereotype.Controller");
        }
    };

    /**
     * 请求接口注解
     */
    public static final Set<String> MAPPING_ANNOTATIONS = new HashSet<>() {
        {
            add("org.springframework.web.bind.annotation.GetMapping");
            add("org.springframework.web.bind.annotation.PostMapping");
            add("org.springframework.web.bind.annotation.DeleteMapping");
            add("org.springframework.web.bind.annotation.PatchMapping");
            add("org.springframework.web.bind.annotation.RequestMapping");
        }
    };

    /**
     * Bean注解
     */
    public static final String BEAN_ANNOTATION = "org.springframework.context.annotation.Bean";

    public static final String REPOSITORY_ANNOTATION = "org.springframework.stereotype.Repository";

    /**
     * Bean对象类型注解
     */
    public static final Set<String> BEAN_TYPE_ANNOTATIONS = new HashSet<>() {
        {
            add("org.springframework.stereotype.Component");
            add(REPOSITORY_ANNOTATION);
            add("org.springframework.stereotype.Service");
            add("org.springframework.stereotype.Controller");
            add(SPRING_BOOT_ANNOTATIONS);
            add(BEAN_ANNOTATION);
            addAll(CONFIGURATION_ANNOTATIONS);
            addAll(CONTROLLER_ANNOTATIONS);
            addAll(MAPPING_ANNOTATIONS);
        }
    };

}
