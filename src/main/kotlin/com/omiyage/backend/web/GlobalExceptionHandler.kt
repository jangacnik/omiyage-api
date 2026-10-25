package com.omiyage.backend.web

import org.slf4j.LoggerFactory
import jakarta.validation.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.http.ResponseEntity
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.resource.NoResourceFoundException

/** Keeps error responses in the same `{"error": "..."}` shape MeishiApi uses, so the frontend's
 *  single error-parsing path in apiClient.ts works against either backend. */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<Map<String, String>> {
        val message = e.bindingResult.fieldErrors.joinToString("; ") { "${it.field} ${it.defaultMessage}" }
        return ResponseEntity.badRequest().body(errorBody(HttpStatus.BAD_REQUEST, message))
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(e: ResponseStatusException): ResponseEntity<Map<String, String>> {
        val message = e.reason ?: e.statusCode.toString()
        return ResponseEntity.status(e.statusCode).body(errorBody(e.statusCode, message))
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotAllowed(e: HttpRequestMethodNotSupportedException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(errorBody(HttpStatus.METHOD_NOT_ALLOWED, e.message ?: "method not allowed"))

    // A soft-deleted row is invisible to its own @SQLRestriction, so an eager to-one association
    // still pointing at it (a NOT NULL FK with no cascade) makes Hibernate throw this instead of
    // just returning null - e.g. a CombineRequest whose target shipment got deleted after the
    // request was created. That's a missing resource, not a server fault.
    @ExceptionHandler(jakarta.persistence.EntityNotFoundException::class)
    fun handleEntityNotFound(e: jakarta.persistence.EntityNotFoundException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(HttpStatus.NOT_FOUND, "not found"))

    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
        MissingServletRequestParameterException::class,
        MissingServletRequestPartException::class,
        MissingRequestHeaderException::class,
        ConstraintViolationException::class,
    )
    fun handleBadRequest(e: Exception): ResponseEntity<Map<String, String>> =
        error(HttpStatus.BAD_REQUEST, "bad request")

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleUploadTooLarge(e: MaxUploadSizeExceededException): ResponseEntity<Map<String, String>> =
        error(HttpStatus.PAYLOAD_TOO_LARGE, "file too large")

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleUnsupportedMediaType(e: HttpMediaTypeNotSupportedException): ResponseEntity<Map<String, String>> =
        error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported media type")

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(e: NoResourceFoundException): ResponseEntity<Map<String, String>> =
        error(HttpStatus.NOT_FOUND, "not found")

    @ExceptionHandler(DataIntegrityViolationException::class, OptimisticLockingFailureException::class)
    fun handleConflict(e: Exception): ResponseEntity<Map<String, String>> {
        log.warn("conflicting write: {}", e.javaClass.simpleName)
        return error(HttpStatus.CONFLICT, "conflict")
    }

    // MeishiApi unreachable or timing out: a gateway problem, not a fault in this service.
    @ExceptionHandler(ResourceAccessException::class)
    fun handleUpstreamUnavailable(e: ResourceAccessException): ResponseEntity<Map<String, String>> {
        log.warn("upstream unavailable: {}", e.message)
        return error(HttpStatus.SERVICE_UNAVAILABLE, "upstream service unavailable")
    }

    @ExceptionHandler(HttpServerErrorException::class)
    fun handleUpstreamError(e: HttpServerErrorException): ResponseEntity<Map<String, String>> {
        log.warn("upstream returned {}", e.statusCode)
        return error(HttpStatus.BAD_GATEWAY, "upstream service error")
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<Map<String, String>> {
        log.error("unhandled exception", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(HttpStatus.INTERNAL_SERVER_ERROR, "internal error"))
    }

    private fun error(status: HttpStatus, message: String) =
        ResponseEntity.status(status).body(errorBody(status, message))
}
