package com.omiyage.backend.web

import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode

/** `error` is the human-readable message, `code` a stable machine-readable value derived from the status. */
fun errorBody(status: HttpStatusCode, message: String): Map<String, String> =
    mapOf("error" to message, "code" to (HttpStatus.entries.firstOrNull { it.value() == status.value() }?.name?.lowercase() ?: "error"))
