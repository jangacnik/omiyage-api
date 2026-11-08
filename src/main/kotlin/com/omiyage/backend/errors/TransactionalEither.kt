package com.omiyage.backend.errors

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.interceptor.TransactionAspectSupport

/** Runs a use case that signals expected failures through [Raise]. A `Left` is not an exception,
 *  so the surrounding `@Transactional` would commit whatever the block changed before failing;
 *  mark that transaction rollback-only instead, as the thrown exceptions used to. */
inline fun <A> transactionalEither(block: Raise<DomainError>.() -> A): Either<DomainError, A> =
    either(block).also { if (it.isLeft()) rollbackCurrentTransaction() }

fun rollbackCurrentTransaction() {
    try {
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()
    } catch (noTransaction: NoTransactionException) {
        // Not running inside a transaction (e.g. a plain unit test): nothing to roll back.
    }
}
