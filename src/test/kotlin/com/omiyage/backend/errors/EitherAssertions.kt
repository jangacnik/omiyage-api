package com.omiyage.backend.errors

import arrow.core.Either
import com.omiyage.backend.web.HttpFailure
import com.omiyage.backend.web.toHttpFailure
import org.springframework.http.HttpStatusCode

/** The success value, failing the test with the error if the use case returned a `Left`. */
fun <A> Either<DomainError, A>.orFail(): A =
    fold({ throw AssertionError("expected success but got $it") }, { it })

/** The error, failing the test if the use case succeeded. */
fun <A> Either<DomainError, A>.errorOrFail(): DomainError =
    fold({ it }, { throw AssertionError("expected a domain error but got $it") })

/** The HTTP shape of the error a use case returned, for tests that pin status codes. */
fun failureOf(block: () -> Either<DomainError, *>): HttpFailure = block().errorOrFail().toHttpFailure()

val HttpFailure.statusCode: HttpStatusCode get() = status
