package com.omiyage.backend.errors

import org.springframework.http.HttpStatusCode

sealed interface UserError : DomainError {
    /** MeishiApi refused a profile call; its status and message are passed on unchanged. */
    data class UpstreamRejected(val status: HttpStatusCode, val message: String) : UserError
    data object EmptyUpstreamResponse : UserError
}
