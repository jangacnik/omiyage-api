package com.omiyage.backend.errors

import arrow.core.Either

/** The success value, failing the test with the error if the use case returned a `Left`. */
fun <A> Either<DomainError, A>.orFail(): A =
    fold({ throw AssertionError("expected success but got $it") }, { it })

/** The error, failing the test if the use case succeeded. */
fun <A> Either<DomainError, A>.errorOrFail(): DomainError =
    fold({ it }, { throw AssertionError("expected a domain error but got $it") })
