package com.omiyage.backend.web

import com.omiyage.backend.security.MeishiPrincipal
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

private val READ_ONLY_METHODS = setOf("GET", "HEAD", "OPTIONS")

/** One audit line per admin write (prices, statuses, paid flags, deletes, ...): who, what, outcome. */
class AdminAuditInterceptor : HandlerInterceptor {

    private val auditLog = LoggerFactory.getLogger("audit")

    override fun afterCompletion(request: HttpServletRequest, response: HttpServletResponse, handler: Any, ex: Exception?) {
        if (request.method in READ_ONLY_METHODS) return
        val admin = SecurityContextHolder.getContext().authentication?.principal as? MeishiPrincipal
        auditLog.info(
            "admin_mutation user={} method={} path={} status={}",
            admin?.id, request.method, request.requestURI, response.status,
        )
    }
}

@Component
class AdminAuditConfig : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(AdminAuditInterceptor()).addPathPatterns("/api/admin/**")
    }
}
