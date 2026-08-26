package com.lifeos.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Value("${info.app.version:0.1.0}")
    private String version;

    @Bean
    public OpenAPI lifeOsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("lifeOS-ai")
                        .version(version)
                        .description("Life OS REST. Money = integer cents. Times = UTC ISO with Z. "
                                + "Mutations need header X-Life-Handle (WeChat peer id)."))
                .addSecurityItem(new SecurityRequirement().addList("X-Life-Handle"))
                .components(new Components().addSecuritySchemes("X-Life-Handle",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Life-Handle")
                                .description("people.handle / OpenClaw WeChat peer id")));
    }
}
