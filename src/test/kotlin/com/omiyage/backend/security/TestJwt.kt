package com.omiyage.backend.security

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Hand-signs HS256 tokens the same way MeishiApi (Vert.x) does, for use as test fixtures. */
object TestJwt {

    private val mapper = ObjectMapper()

    fun sign(
        secret: String,
        subject: String = UUID.randomUUID().toString(),
        nickname: String = "bob",
        role: String = "USER",
        expiresAtEpochSeconds: Long = System.currentTimeMillis() / 1000 + 60,
    ): String {
        val header = encode(mapOf("alg" to "HS256", "typ" to "JWT"))
        val payload = encode(mapOf("sub" to subject, "nickname" to nickname, "role" to role, "exp" to expiresAtEpochSeconds))
        val signingInput = "$header.$payload"

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(signingInput.toByteArray(Charsets.US_ASCII)))

        return "$signingInput.$signature"
    }

    private fun encode(value: Any): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(value))
}
