package com.omiyage.backend.security

import org.springframework.security.core.context.SecurityContextHolder

object CurrentUser {
    fun principal(): MeishiPrincipal =SecurityContextHolder.getContext().authentication!!.principal as MeishiPrincipal
}
