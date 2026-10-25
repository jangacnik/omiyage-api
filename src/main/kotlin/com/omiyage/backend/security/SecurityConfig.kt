package com.omiyage.backend.security

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import com.omiyage.backend.web.errorBody
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

private const val CORS_MAX_AGE_SECONDS = 3600L

@Configuration
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val objectMapper: ObjectMapper,
    @Value("\${omiyage.cors.allowed-origin}") private val allowedOrigin: String,
    environment: Environment,
) {

    private val apiDocsPublic = environment.acceptsProfiles(Profiles.of("dev"))

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            csrf { disable() }
            cors { configurationSource = corsConfigurationSource() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize("/api/auth/**", permitAll)
                authorize("/actuator/health/**", permitAll)
                authorize("/error", permitAll)
                if (apiDocsPublic) {
                    authorize("/swagger-ui/**", permitAll)
                    authorize("/v3/api-docs/**", permitAll)
                }
                authorize(HttpMethod.GET, "/api/images/**", permitAll)
                authorize("/actuator/prometheus", hasRole("ADMIN"))
                authorize("/api/admin/**", hasRole("ADMIN"))
                authorize("/api/**", authenticated)
                authorize(anyRequest, denyAll)
            }
            exceptionHandling {
                authenticationEntryPoint = jsonAuthenticationEntryPoint()
                accessDeniedHandler = jsonAccessDeniedHandler()
            }
            addFilterBefore<UsernamePasswordAuthenticationFilter>(jwtAuthenticationFilter)
        }
        return http.build()
    }

    private fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = listOf(allowedOrigin)
            allowedMethods = listOf("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type")
            maxAge = CORS_MAX_AGE_SECONDS
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", config)
        }
    }

    private fun jsonAuthenticationEntryPoint() = org.springframework.security.web.AuthenticationEntryPoint { _, response, _ ->
        writeJsonError(response, 401, "unauthorized")
    }

    private fun jsonAccessDeniedHandler() = org.springframework.security.web.access.AccessDeniedHandler { _, response, _ ->
        writeJsonError(response, 403, "forbidden")
    }

    private fun writeJsonError(response: jakarta.servlet.http.HttpServletResponse, status: Int, message: String) {
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write(objectMapper.writeValueAsString(errorBody(HttpStatus.valueOf(status), message)))
    }
}
