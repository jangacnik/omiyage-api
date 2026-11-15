package com.omiyage.backend.errors

sealed interface MediaError : DomainError {
    data object EmptyFile : MediaError
    data object ContentTypeMismatch : MediaError
    data class UnknownImageKey(val key: String) : MediaError
    data object DailyQuotaReached : MediaError
    data object ImageNotFound : MediaError
}
