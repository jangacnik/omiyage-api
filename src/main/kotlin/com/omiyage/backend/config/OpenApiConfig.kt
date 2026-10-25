package com.omiyage.backend.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private const val BEARER_SCHEME = "bearerAuth"

@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .components(
            Components()
                .addSecuritySchemes(
                    BEARER_SCHEME,
                    SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"),
                )
                .addSchemas(
                    "Error",
                    ObjectSchema()
                        .addProperty("error", StringSchema().description("Human-readable message"))
                        .addProperty("code", StringSchema().description("Stable machine-readable code, e.g. not_found")),
                ),
        )
        .addSecurityItem(SecurityRequirement().addList(BEARER_SCHEME))
}
