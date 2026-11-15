package com.omiyage.backend.errors

/** Why a signed bot request was refused. Every case is a 401; the case is only logged, never sent back. */
sealed interface BotAuthError : DomainError {
    data object NotConfigured : BotAuthError
    data object MissingHeaders : BotAuthError
    data object MalformedTimestamp : BotAuthError
    data object StaleTimestamp : BotAuthError
    data object FutureTimestamp : BotAuthError
    data object BadSignature : BotAuthError
}
