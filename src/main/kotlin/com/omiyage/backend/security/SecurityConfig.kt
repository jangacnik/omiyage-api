package com.omiyage.backend.security

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val objectMapper: ObjectMapper,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize("/api/auth/**", permitAll)
                authorize("/actuator/health", permitAll)
                authorize("/swagger-ui/**", permitAll)
                authorize("/v3/api-docs/**", permitAll)
                authorize("/api/admin/**", hasRole("ADMIN"))
                authorize("/api/**", authenticated)
                authorize(anyRequest, permitAll)
            }
            exceptionHandling {
                authenticationEntryPoint = jsonAuthenticationEntryPoint()
                accessDeniedHandler = jsonAccessDeniedHandler()
            }
            addFilterBefore<UsernamePasswordAuthenticationFilter>(jwtAuthenticationFilter)
        }
        return http.build()
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
        response.writer.write(objectMapper.writeValueAsString(mapOf("error" to message)))
    }
}
