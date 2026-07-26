package com.omiyage.backend.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

/** Keeps error responses in the same `{"error": "..."}` shape MeishiApi uses, so the frontend's
 *  single error-parsing path in apiClient.ts works against either backend. */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<Map<String, String>> {
        val message = e.bindingResult.fieldErrors.joinToString("; ") { "${it.field} ${it.defaultMessage}" }
        return ResponseEntity.badRequest().body(mapOf("error" to message))
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(e: ResponseStatusException): ResponseEntity<Map<String, String>> {
        val message = e.reason ?: e.statusCode.toString()
        return ResponseEntity.status(e.statusCode).body(mapOf("error" to message))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<Map<String, String>> {
        log.error("unhandled exception", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf("error" to "internal error"))
    }
}
