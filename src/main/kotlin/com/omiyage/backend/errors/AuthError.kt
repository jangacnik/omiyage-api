package com.omiyage.backend.errors

sealed interface AuthError : DomainError {
    data object InvalidCredentials : AuthError
    data object InvalidAuthorizationCode : AuthError
    data object TooManyAttempts : AuthError
    /** MeishiApi answered 429 to a password login; [retryAfterSeconds] is its Retry-After when it sent one. */
    data class UpstreamRateLimited(val retryAfterSeconds: Long?) : AuthError
    data object SsoNotEnabledUpstream : AuthError
    data object SsoDisabled : AuthError
    data object InvalidRequest : AuthError
    data object AccountFrozen : AuthError
    data object UnusableIdentityToken : AuthError
}
