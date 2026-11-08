package com.omiyage.backend.auth

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import com.omiyage.backend.errors.AuthError
import com.omiyage.backend.errors.DomainError
import com.omiyage.backend.security.JwtVerificationException
import com.omiyage.backend.security.JwtVerifier
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest

private const val NICKNAME_HASH_LENGTH = 8

/** Short, stable fingerprint of a submitted login name: people type passwords into that field, so it is never logged as text. */
fun nicknameHash(nickname: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(nickname.trim().lowercase().toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(NICKNAME_HASH_LENGTH)

@Service
class AuthService(private val meishi: MeishiAuthClient, private val jwtVerifier: JwtVerifier) {

    private val securityLog = LoggerFactory.getLogger("security")

    /** Logs in against MeishiApi, then rejects tokens for accounts Meishi has frozen — MeishiApi's
     *  JWTs carry no status claim, so a frozen account's existing token would otherwise stay valid
     *  until it expires. */
    fun login(nickname: String, password: String, clientIp: String): Either<DomainError, String> = either {
        val token = meishi.login(nickname, password)
            .onLeft {
                securityLog.warn("login_failed nicknameHash={} error={} clientIp={}", nicknameHash(nickname), it::class.simpleName, clientIp)
            }
            .bind()
        rejectFrozen(token)
    }

    /** Exchanges a hosted-login code for the Meishi JWT. MeishiApi already refuses locked, inactive and frozen
     *  accounts at the token endpoint; the frozen check below is kept so both login paths behave the same. */
    fun loginWithMeishiCode(
        clientId: String,
        code: String,
        codeVerifier: String,
        redirectUri: String,
        clientIp: String,
    ): Either<DomainError, String> = either {
        val token = meishi.exchangeCode(clientId, code, codeVerifier, redirectUri)
            .onLeft { securityLog.warn("sso_exchange_failed error={} clientIp={}", it::class.simpleName, clientIp) }
            .bind()
        requireTokenWeAccept(token)
        rejectFrozen(token)
    }

    /** The relay must not hand out a token that omiyage-api would itself reject on the next request. */
    private fun Raise<DomainError>.requireTokenWeAccept(token: String) {
        try {
            jwtVerifier.verify(token)
        } catch (e: JwtVerificationException) {
            securityLog.warn("sso_token_rejected reason={}", e.message)
            raise(AuthError.UnusableIdentityToken)
        }
    }

    private fun Raise<DomainError>.rejectFrozen(token: String): String {
        val me = meishi.getMe(token).bind()
        if (me.status == "FROZEN") {
            securityLog.warn("account_status_blocked userId={} status={}", me.id, me.status)
            raise(AuthError.AccountFrozen)
        }
        return token
    }
}
