package com.omiyage.backend.shipment

import com.omiyage.backend.security.CurrentUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Separate from MemberShipmentController ("/api/shipments/mine") since this lists other
 *  members' shipments, not the caller's own. */
@RestController
@RequestMapping("/api/shipments/browsable")
class BrowsableShipmentController(private val service: ShipmentService) {

    @GetMapping
    fun list(@RequestHeader("Authorization") authHeader: String): List<BrowsableShipmentDto> =
        service.browsableForMember(CurrentUser.principal().id, authHeader)
}
