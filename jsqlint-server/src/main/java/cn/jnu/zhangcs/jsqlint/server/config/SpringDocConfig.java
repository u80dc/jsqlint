package cn.jnu.zhangcs.jsqlint.server.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring DOC 配置
 *
 * @author zhangcs
 */
@Configuration
public class SpringDocConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(apiInfo())
                .components(components())
                .addSecurityItem(securityRequirement());
    }

    /**
     * API 信息
     */
    private Info apiInfo() {
        return new Info()
                .title("JSQLint API 文档")
                .description("SQL治理中心 RESTful API 接口文档")
                .version("v1.0.0")
                .contact(new Contact()
                        .name("zhangcs")
                        .email("hi.zhangcs@gmail.com"))
                .license(new License()
                        .name("Apache 2.0")
                        .url("https://www.apache.org/licenses/LICENSE-2.0.html"));
    }

    /**
     * 安全组件配置（JWT认证）
     */
    private Components components() {
        return new Components()
                .addSecuritySchemes("Bearer Token",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("请输入 JWT Token，无需添加 'Bearer ' 前缀")
                );
    }

    /**
     * 全局安全要求（所有接口都需要认证）
     */
    private SecurityRequirement securityRequirement() {
        return new SecurityRequirement().addList("Bearer Token");
    }
}
