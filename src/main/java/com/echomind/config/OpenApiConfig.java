package com.echomind.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI echoMindOpenAPI() {
        return new OpenAPI()
                .components(new Components()
                        .addSecuritySchemes("UserKey", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-User-Key"))
                        .addSecuritySchemes("AdminKey", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-API-Key")))
                .info(new Info()
                        .title("EchoMind Java API")
                        .version("0.1.0")
                        .description("EchoMind Java 智能客服系统接口文档，支持在线调试 /chat、/search、知识库、监控和评测接口。")
                        .license(new License().name("Internal Project")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local Java App"),
                        new Server().url("http://localhost:8081").description("Local Nginx Proxy")
                ));
    }

    @Bean
    public OpenApiCustomizer endpointCredentials() {
        return api -> {
            if (api.getPaths() == null) return;
            api.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
                if (path.equals("/health") || path.equals("/docs")) {
                    operation.setSecurity(List.of());
                } else {
                    String scheme = path.equals("/chat") || path.equals("/search") ? "UserKey" : "AdminKey";
                    operation.setSecurity(List.of(new SecurityRequirement().addList(scheme)));
                }
            }));
        };
    }
}
