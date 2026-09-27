package com.javaded78.urlshorter.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata (Task 12.1): the service identity every developer sees
 * in Swagger UI and the {@code /v3/api-docs} contract.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI urlShortenerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("URL Shortener API")
                .description("Short links with custom aliases, expiration, click counting "
                        + "and Redis-accelerated redirects.")
                .version("1.0.0"));
    }
}
