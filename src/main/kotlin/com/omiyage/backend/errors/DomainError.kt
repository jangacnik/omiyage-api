package com.omiyage.backend.errors

/** An expected failure of a use case. One sealed hierarchy per feature area; the web layer maps
 *  every case to an HTTP response in exactly one place (see web/DomainErrorMapper.kt). */
sealed interface DomainError
