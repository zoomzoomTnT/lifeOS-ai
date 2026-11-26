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

    @Value(OpenClawSwitch.VALUE)
    private boolean openclawEnabled = true;

    @Bean
    public OpenAPI lifeOsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("lifeOS-ai")
                        .version(version)
                        .description("Life OS REST. Money = integer cents. Times = UTC ISO with Z. "
                                + "Every /api call needs Authorization: Bearer <lifeOS API key> (lok_...), scoped per "
                                + "client (finance/fridge/memos/stocks/ops :read/:write). The acting person comes from the "
                                + "key; X-Life-Handle is ignored. Risky calls answer 403 approval_required: ask zoomzoom "
                                + "in chat; once the admin client approves, repeat the request with Life-Approval: <id>. "
                                + "/api/admin/** and tier-3 actions: the admin client (lifeOS Admin bot) only. "
                                + "See docs/auth.md."
                                + (openclawEnabled ? "" : " OpenClaw integration is off (OPENCLAW_ENABLED=false).")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .description("lifeOS API key: lok_<key_id>_<secret> (minted by the admin client via /api/admin)")));
    }
}
