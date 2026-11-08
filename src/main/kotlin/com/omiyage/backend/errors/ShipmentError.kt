package com.omiyage.backend.errors

import java.util.UUID

sealed interface ShipmentError : DomainError {
    data object ShipmentNotFound : ShipmentError
    data object CombineNeedsOrders : ShipmentError
    data object OrderNotOpenForShipment : ShipmentError
    data object OrderShippedForShipment : ShipmentError
    data object CombineNeedsTwoShipments : ShipmentError
    data object ShipmentShippedCannotCombine : ShipmentError
    data object ShipmentShippedCannotSplit : ShipmentError
    data class OrderNotInShipment(val orderId: UUID) : ShipmentError
    data object SplitNeedsSomeButNotAllOrders : ShipmentError
    data object NotOwnerCannotRename : ShipmentError
    data object NotOwnerCannotSetShipping : ShipmentError
    data object ShippingLockedOnceShipped : ShipmentError
    data object NotOwnerCannotMarkArrived : ShipmentError
    data object OnlyShippedCanArrive : ShipmentError
    data object OrderNotInShipmentToLeave : ShipmentError
    data object LeaveAfterShipped : ShipmentError
    data object OrdersNotReadyToShip : ShipmentError
    data object AttachToShippedShipment : ShipmentError
    data object ShippingRateNotFound : ShipmentError
    data object ShippingRateExists : ShipmentError
}
