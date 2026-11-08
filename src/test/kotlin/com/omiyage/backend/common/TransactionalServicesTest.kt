package com.omiyage.backend.common

import arrow.core.Either
import com.omiyage.backend.basket.BasketService
import com.omiyage.backend.catalog.CategoryService
import com.omiyage.backend.catalog.InventoryService
import com.omiyage.backend.catalog.ItemTemplateService
import com.omiyage.backend.combine.CombineRequestService
import com.omiyage.backend.order.OrderService
import com.omiyage.backend.shipment.ShipmentService
import com.omiyage.backend.shipment.ShippingRateService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.transaction.annotation.Transactional
import java.lang.reflect.Modifier

/** A `Left` only rolls back a transaction that exists (see transactionalEither), so every
 *  database-backed service method that returns an `Either` must run in one. */
class TransactionalServicesTest {

    private val services = listOf(
        CategoryService::class.java,
        ItemTemplateService::class.java,
        InventoryService::class.java,
        OrderService::class.java,
        BasketService::class.java,
        ShipmentService::class.java,
        ShippingRateService::class.java,
        CombineRequestService::class.java,
    )

    @Test
    fun `every Either-returning service method is transactional`() {
        val missing = services.flatMap { service ->
            service.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && it.returnType == Either::class.java }
                .filter {
                    !AnnotatedElementUtils.hasAnnotation(it, Transactional::class.java) &&
                        !AnnotatedElementUtils.hasAnnotation(service, Transactional::class.java)
                }
                .map { "${service.simpleName}.${it.name}" }
        }

        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `the check sees the services' Either-returning methods`() {
        val seen = services.sumOf { service ->
            service.declaredMethods.count { Modifier.isPublic(it.modifiers) && it.returnType == Either::class.java }
        }

        assertTrue(seen > 40, "expected the services to expose many Either-returning methods, saw $seen")
    }
}
