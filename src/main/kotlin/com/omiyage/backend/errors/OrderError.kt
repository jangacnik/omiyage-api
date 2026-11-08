package com.omiyage.backend.errors

import com.omiyage.backend.order.OrderStatus
import java.util.UUID

sealed interface OrderError : DomainError {
    data object OrderNotFound : OrderError
    data class OrderIdNotFound(val id: UUID) : OrderError
    data object OrderItemNotFound : OrderError
    data class OrderItemIdNotFound(val id: UUID) : OrderError
    data class StatusChangeNotAllowed(val from: OrderStatus, val to: OrderStatus) : OrderError
    data object OrderClosed : OrderError
    data object ItemLockedForEdit : OrderError
    data object ItemLockedForRemoval : OrderError
    data object ItemNotCustom : OrderError
    data object InventoryItemAlreadyExists : OrderError
    data object MoveNeedsSomeButNotAllItems : OrderError
    data object MoveTargetIsSource : OrderError
    data object MoveTargetOfOtherUser : OrderError
    data object CancelHasLockedItems : OrderError
    data object SplitNeedsSomeButNotAllItems : OrderError
    data object SubmitHasPreorderItem : OrderError
    data object MergeNeedsTwoOrders : OrderError
    data object MergeAcrossUsers : OrderError
    data object MergeShippedOrder : OrderError
    data object MergeBothSubmitted : OrderError
    data object ItemNeedsInventoryItemOrTitle : OrderError
    data object CustomItemNeedsCondition : OrderError
    data object PreorderNeedsReleaseDate : OrderError
}
