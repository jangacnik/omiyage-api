package com.omiyage.backend.auth

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest

private const val NICKNAME_HASH_LENGTH = 8

/** Short, stable fingerprint of a submitted login name: people type passwords into that field, so it is never logged as text. */
fun nicknameHash(nickname: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(nickname.trim().lowercase().toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(NICKNAME_HASH_LENGTH)

@Service
class AuthService(private val meishi: MeishiAuthClient) {

    private val securityLog = LoggerFactory.getLogger("security")

    /** Logs in against MeishiApi, then rejects tokens for accounts Meishi has frozen — MeishiApi's
     *  JWTs carry no status claim, so a frozen account's existing token would otherwise stay valid
     *  until it expires. */
    fun login(nickname: String, password: String, clientIp: String): String {
        val token = try {
            meishi.login(nickname, password)
        } catch (e: ResponseStatusException) {
            securityLog.warn("login_failed nicknameHash={} status={} clientIp={}", nicknameHash(nickname), e.statusCode.value(), clientIp)
            throw e
        }
        return rejectFrozen(token)
    }

    /** Exchanges a hosted-login code for the Meishi JWT. MeishiApi already refuses locked, inactive and frozen
     *  accounts at the token endpoint; the frozen check below is kept so both login paths behave the same. */
    fun loginWithMeishiCode(clientId: String, code: String, codeVerifier: String, redirectUri: String, clientIp: String): String {
        val token = try {
            meishi.exchangeCode(clientId, code, codeVerifier, redirectUri)
        } catch (e: ResponseStatusException) {
            securityLog.warn("sso_exchange_failed status={} clientIp={}", e.statusCode.value(), clientIp)
            throw e
        }
        return rejectFrozen(token)
    }

    private fun rejectFrozen(token: String): String {
        val me = meishi.getMe(token)
        if (me.status == "FROZEN") {
            securityLog.warn("account_status_blocked userId={} status={}", me.id, me.status)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "account is frozen")
        }
        return token
    }
}
