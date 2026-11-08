package com.omiyage.backend.errors

import arrow.core.raise.ensure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TransactionalEitherTest {

    @Test
    fun `a success is returned as is`() {
        assertEquals(7, transactionalEither { 7 }.orFail())
    }

    @Test
    fun `a raised error is returned without a transaction to roll back`() {
        val result = transactionalEither<Int> {
            ensure(false) { CatalogError.InventoryItemNotFound }
            7
        }

        assertEquals(CatalogError.InventoryItemNotFound, result.errorOrFail())
    }
}
