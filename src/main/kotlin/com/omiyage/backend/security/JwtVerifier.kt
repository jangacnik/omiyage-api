package com.omiyage.backend.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.omiyage.backend.config.MeishiProperties
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class JwtVerificationException(message: String) : RuntimeException(message)

/**
 * Verifies JWTs issued by MeishiApi by hand rather than via a JWT library: MeishiApi (Vert.x)
 * signs HS256 tokens using the raw UTF-8 bytes of the shared secret as the HMAC key with no
 * minimum-length enforcement, but libraries like jjwt reject short keys as "unsafe" even for
 * verification-only use — which would make Omiyage unable to verify tokens signed with Meishi's
 * own (short, dev-default) secret. Hand-rolled HMAC-SHA256 verification matches Vert.x exactly.
 */
@Component
class JwtVerifier(props: MeishiProperties, private val objectMapper: ObjectMapper) {

    private val keyBytes = props.jwtSecret.toByteArray(Charsets.UTF_8)

    fun verify(token: String): MeishiPrincipal {
        val parts = token.split(".")
        if (parts.size != 3) throw JwtVerificationException("malformed token")
        val (headerB64, payloadB64, signatureB64) = parts

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        val expectedSignature = mac.doFinal("$headerB64.$payloadB64".toByteArray(Charsets.US_ASCII))
        val actualSignature = decodeBase64Url(signatureB64) ?: throw JwtVerificationException("malformed signature")
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
            throw JwtVerificationException("bad signature")
        }

        val payloadBytes = decodeBase64Url(payloadB64) ?: throw JwtVerificationException("malformed payload")
        val claims = try {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(payloadBytes, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            throw JwtVerificationException("payload is not valid JSON")
        }

        val exp = (claims["exp"] as? Number)?.toLong() ?: throw JwtVerificationException("missing exp claim")
        if (Instant.now().epochSecond >= exp) throw JwtVerificationException("token expired")

        val sub = claims["sub"] as? String ?: throw JwtVerificationException("missing sub claim")
        val nickname = claims["nickname"] as? String ?: throw JwtVerificationException("missing nickname claim")
        val role = claims["role"] as? String ?: throw JwtVerificationException("missing role claim")

        val id = try {
            UUID.fromString(sub)
        } catch (e: IllegalArgumentException) {
            throw JwtVerificationException("invalid sub claim")
        }
        val parsedRole = try {
            Role.valueOf(role)
        } catch (e: IllegalArgumentException) {
            throw JwtVerificationException("invalid role claim")
        }

        return MeishiPrincipal(id, nickname, parsedRole)
    }

    private fun decodeBase64Url(segment: String): ByteArray? {
        val padded = when (segment.length % 4) {
            2 -> "$segment=="
            3 -> "$segment="
            else -> segment
        }
        return try {
            Base64.getUrlDecoder().decode(padded)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
