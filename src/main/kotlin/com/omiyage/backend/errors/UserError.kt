package com.omiyage.backend.errors

sealed interface UserError : DomainError {
    /** MeishiApi refused a profile call; its status and message are passed on unchanged. */
    data class UpstreamRejected(val status: Int, val message: String) : UserError
    data object EmptyUpstreamResponse : UserError
}
