package com.deployhub.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** API 명세. 태그는 기능 분류를 그대로 쓴다. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI deployHubOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("DeployHub API")
                        .description("배포패키지 자동화 시스템 API 명세")
                        .version("v1"));
    }
}
