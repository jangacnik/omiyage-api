package com.omiyage.backend.errors

sealed interface CombineError : DomainError {
    data object RequestNotFound : CombineError
    data object RequestNotPending : CombineError
    data object AlreadyOnShipment : CombineError
    data object TargetShipmentShipped : CombineError
    data object DuplicatePendingRequest : CombineError
    data object MemberAlreadyOnShipment : CombineError
    data object OrderNotOpen : CombineError
    data object OrderAlreadyShipped : CombineError
}
