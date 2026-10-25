package com.omiyage.backend.config

import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

@Component
class JwtSecretValidator(props: MeishiProperties, environment: Environment) {

    init {
        val secret = props.jwtSecret
        check(secret.isNotBlank() && !secret.startsWith("\${")) { "MEISHI_JWT_SECRET is not set" }
        val relaxed = environment.acceptsProfiles(Profiles.of("dev", "test"))
        if (!relaxed) {
            check(secret != DEV_SECRET) { "MEISHI_JWT_SECRET must not be the dev default outside the dev/test profiles" }
            check(secret.toByteArray(Charsets.UTF_8).size >= MIN_SECRET_BYTES) {
                "MEISHI_JWT_SECRET must be at least $MIN_SECRET_BYTES bytes outside the dev/test profiles"
            }
        }
    }

    private companion object {
        const val DEV_SECRET = "dev-secret-change-me"
        const val MIN_SECRET_BYTES = 32
    }
}
