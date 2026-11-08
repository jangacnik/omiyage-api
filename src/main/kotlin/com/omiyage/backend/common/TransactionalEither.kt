package com.omiyage.backend.common

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import com.omiyage.backend.errors.DomainError
import org.springframework.transaction.interceptor.TransactionAspectSupport
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Runs a use case that signals expected failures through [Raise]. A `Left` is not an exception,
 *  so the surrounding `@Transactional` would commit whatever the block changed before failing;
 *  mark that transaction rollback-only instead, as the thrown exceptions used to. Every
 *  `Either`-returning write method that uses this must itself be `@Transactional`
 *  (see TransactionalServicesTest). */
inline fun <A> transactionalEither(block: Raise<DomainError>.() -> A): Either<DomainError, A> =
    either(block).also { if (it.isLeft()) rollbackCurrentTransaction() }

fun rollbackCurrentTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()
    }
}
