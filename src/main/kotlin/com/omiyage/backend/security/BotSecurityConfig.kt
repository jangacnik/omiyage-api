package com.omiyage.backend.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.omiyage.backend.web.errorBody
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

const val BOT_PATH_PATTERN = "/api/bot/**"

/** Its own filter chain, ahead of the JWT one: /api/bot/** takes a signed request instead of a token,
 *  and anything without a verified signature is a 401. */
@Configuration
class BotSecurityConfig(private val verifier: RequestSignatureVerifier, private val objectMapper: ObjectMapper) {

    @Bean
    @Order(1)
    fun botSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            securityMatcher(BOT_PATH_PATTERN)
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize(anyRequest, hasRole(BOT_ROLE))
            }
            exceptionHandling {
                authenticationEntryPoint = AuthenticationEntryPoint { _, response, _ ->
                    response.status = HttpStatus.UNAUTHORIZED.value()
                    response.contentType = MediaType.APPLICATION_JSON_VALUE
                    response.writer.write(objectMapper.writeValueAsString(errorBody(HttpStatus.UNAUTHORIZED, "unauthorized")))
                }
            }
            addFilterBefore<UsernamePasswordAuthenticationFilter>(BotSignatureFilter(verifier))
        }
        return http.build()
    }
}
