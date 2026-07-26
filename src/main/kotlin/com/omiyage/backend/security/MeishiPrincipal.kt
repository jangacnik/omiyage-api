package com.omiyage.backend.security

import java.util.UUID

data class MeishiPrincipal(
    val id: UUID,
    val nickname: String,
    val role: Role,
)
