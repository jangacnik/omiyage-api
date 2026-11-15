package com.omiyage.backend.errors

sealed interface AuthError : DomainError {
    data object InvalidCredentials : AuthError
    data object InvalidAuthorizationCode : AuthError
    data object TooManyAttempts : AuthError
    data object SsoNotEnabledUpstream : AuthError
    data object SsoDisabled : AuthError
    data object InvalidRequest : AuthError
    data object AccountFrozen : AuthError
    data object UnusableIdentityToken : AuthError
}
