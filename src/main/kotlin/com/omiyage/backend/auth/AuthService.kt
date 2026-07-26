package com.omiyage.backend.auth

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class AuthService(private val meishi: MeishiAuthClient) {

    /** Logs in against MeishiApi, then rejects tokens for accounts Meishi has frozen — MeishiApi's
     *  JWTs carry no status claim, so a frozen account's existing token would otherwise stay valid
     *  until it expires. */
    fun login(nickname: String, password: String): String {
        val token = meishi.login(nickname, password)
        val me = meishi.getMe(token)
        if (me.status == "FROZEN") {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "account is frozen")
        }
        return token
    }
}
