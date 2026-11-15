package com.omiyage.backend.order

import com.omiyage.backend.web.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Read-only, for omiyage-bot. Authenticated by BotSecurityConfig (signed request), not by a JWT. */
@RestController
@RequestMapping("/api/bot/orders")
class BotOrderController(private val service: UnshippedOrderService) {

    @GetMapping
    @Operation(security = [SecurityRequirement(name = "requestSignature")])
    fun listUnshipped(
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false, defaultValue = "25") size: Int,
    ): PageResponse<BotOrderDto> = service.list(page, size)
}
