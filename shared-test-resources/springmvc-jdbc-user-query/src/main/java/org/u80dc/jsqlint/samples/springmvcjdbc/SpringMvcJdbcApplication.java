package org.u80dc.jsqlint.samples.springmvcjdbc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 使用 Spring Boot 启动 Spring MVC、内嵌 Tomcat 和 JDBC 数据源。
 *
 * @author u80dc
 */
@SpringBootApplication
public class SpringMvcJdbcApplication {
    public static void main(String[] args) {
        SpringApplication.run(SpringMvcJdbcApplication.class, args);
    }
}
