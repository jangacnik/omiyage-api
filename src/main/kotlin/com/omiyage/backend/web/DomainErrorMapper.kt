package com.omiyage.backend.web

import arrow.core.Either
import com.omiyage.backend.errors.AuthError
import com.omiyage.backend.errors.BasketError
import com.omiyage.backend.errors.CatalogError
import com.omiyage.backend.errors.CombineError
import com.omiyage.backend.errors.DomainError
import com.omiyage.backend.errors.MediaError
import com.omiyage.backend.errors.OrderError
import com.omiyage.backend.errors.ShipmentError
import com.omiyage.backend.errors.UserError
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode

/** The HTTP shape of a [DomainError]; [errorBody] turns it into the JSON body. */
data class HttpFailure(val status: HttpStatusCode, val message: String)

/** Carries a [DomainError] from a controller to [GlobalExceptionHandler], so controller return
 *  types (and the generated OpenAPI document) stay those of the success case. */
class DomainErrorException(val error: DomainError) : RuntimeException(null, null, false, false)

/** The controller side of an `Either`: the success value, or the error response of its [DomainError]. */
fun <A> Either<DomainError, A>.getOrRespond(): A = fold({ throw DomainErrorException(it) }, { it })

fun DomainError.toHttpFailure(): HttpFailure = when (this) {
    is MediaError -> mediaFailure()
    is CatalogError -> catalogFailure()
    is OrderError -> orderFailure()
    is BasketError -> basketFailure()
    is ShipmentError -> shipmentFailure()
    is CombineError -> combineFailure()
    is AuthError -> authFailure()
    is UserError -> userFailure()
}

private fun failure(status: HttpStatus, message: String) = HttpFailure(status, message)

private fun MediaError.mediaFailure(): HttpFailure = when (this) {
    MediaError.EmptyFile -> failure(HttpStatus.BAD_REQUEST, "file is empty")
    MediaError.ContentTypeMismatch -> failure(HttpStatus.BAD_REQUEST, "file content does not match an allowed image type")
    is MediaError.UnknownImageKey -> failure(HttpStatus.BAD_REQUEST, "unknown image key: $key")
    MediaError.DailyQuotaReached -> failure(HttpStatus.TOO_MANY_REQUESTS, "daily upload limit reached")
    // Historically a bare 404 with no reason, which the handler rendered as the status line.
    MediaError.ImageNotFound -> failure(HttpStatus.NOT_FOUND, HttpStatus.NOT_FOUND.toString())
}

private fun CatalogError.catalogFailure(): HttpFailure = when (this) {
    CatalogError.CategoryNotFound -> failure(HttpStatus.NOT_FOUND, "category not found")
    CatalogError.CategorySlugInUse -> failure(HttpStatus.CONFLICT, "slug already in use")
    CatalogError.ItemTemplateNotFound -> failure(HttpStatus.NOT_FOUND, "item template not found")
    CatalogError.InventoryItemNotFound -> failure(HttpStatus.NOT_FOUND, "inventory item not found")
    CatalogError.PreorderNeedsReleaseDate -> failure(HttpStatus.BAD_REQUEST, "a preorder item must have an expected release date")
    CatalogError.UnknownCategoryIds -> failure(HttpStatus.BAD_REQUEST, "one or more categoryIds do not exist")
    is CatalogError.TagTooLong -> failure(HttpStatus.BAD_REQUEST, "a tag can be at most $maxLength characters")
}

private fun OrderError.orderFailure(): HttpFailure = when (this) {
    OrderError.OrderNotFound -> failure(HttpStatus.NOT_FOUND, "order not found")
    is OrderError.OrderIdNotFound -> failure(HttpStatus.NOT_FOUND, "order not found: $id")
    OrderError.OrderItemNotFound -> failure(HttpStatus.NOT_FOUND, "order item not found")
    is OrderError.OrderItemIdNotFound -> failure(HttpStatus.NOT_FOUND, "order item not found: $id")
    is OrderError.StatusChangeNotAllowed -> failure(HttpStatus.CONFLICT, "cannot change order status from $from to $to")
    OrderError.OrderClosed -> failure(HttpStatus.CONFLICT, "order is closed and can no longer be edited")
    OrderError.ItemLockedForEdit -> failure(HttpStatus.CONFLICT, "item has already been bought or shipped and can no longer be edited")
    OrderError.ItemLockedForRemoval -> failure(HttpStatus.CONFLICT, "item has already been bought or shipped and can no longer be removed")
    OrderError.ItemNotCustom -> failure(HttpStatus.BAD_REQUEST, "only a custom order item can be added to inventory")
    OrderError.InventoryItemAlreadyExists -> failure(HttpStatus.CONFLICT, "an inventory item with this title, condition and link already exists")
    OrderError.MoveNeedsSomeButNotAllItems -> failure(HttpStatus.BAD_REQUEST, "must move at least one item and leave at least one behind")
    OrderError.MoveTargetIsSource -> failure(HttpStatus.BAD_REQUEST, "target order must be different from the source order")
    OrderError.MoveTargetOfOtherUser -> failure(HttpStatus.CONFLICT, "cannot move items to an order belonging to a different user")
    OrderError.CancelHasLockedItems -> failure(HttpStatus.CONFLICT, "order has items already bought or shipped and cannot be cancelled")
    OrderError.SplitNeedsSomeButNotAllItems -> failure(HttpStatus.BAD_REQUEST, "split must move at least one item and leave at least one behind")
    OrderError.SubmitHasPreorderItem ->
        failure(HttpStatus.CONFLICT, "order has a preorder item that has not released yet and cannot be submitted for shipping")
    OrderError.MergeNeedsTwoOrders -> failure(HttpStatus.BAD_REQUEST, "select at least two different orders to merge")
    OrderError.MergeAcrossUsers -> failure(HttpStatus.CONFLICT, "cannot merge orders belonging to different users")
    OrderError.MergeShippedOrder -> failure(HttpStatus.CONFLICT, "an order that has already shipped cannot be merged")
    OrderError.MergeBothSubmitted -> failure(HttpStatus.CONFLICT, "cannot merge two orders that have both already been submitted for shipping")
    OrderError.ItemNeedsInventoryItemOrTitle -> failure(HttpStatus.BAD_REQUEST, "each order item needs an inventoryItemId or a title")
    OrderError.CustomItemNeedsCondition -> failure(HttpStatus.BAD_REQUEST, "condition is required for a custom order item")
    OrderError.PreorderNeedsReleaseDate -> failure(HttpStatus.BAD_REQUEST, "a preorder item must have an expected release date")
}

private fun BasketError.basketFailure(): HttpFailure = when (this) {
    BasketError.LineNeedsItemOrTitle -> failure(HttpStatus.BAD_REQUEST, "a basket line needs an inventory item or a custom title")
    BasketError.CustomLineNeedsTitle -> failure(HttpStatus.BAD_REQUEST, "a custom line needs a title")
    BasketError.BasketEmpty -> failure(HttpStatus.CONFLICT, "basket is empty")
    BasketError.LineNotFound -> failure(HttpStatus.NOT_FOUND, "basket line not found")
}

private fun ShipmentError.shipmentFailure(): HttpFailure = when (this) {
    ShipmentError.ShipmentNotFound -> failure(HttpStatus.NOT_FOUND, "shipment not found")
    ShipmentError.CombineNeedsOrders -> failure(HttpStatus.BAD_REQUEST, "select at least one order to combine")
    ShipmentError.OrderNotOpenForShipment -> failure(HttpStatus.CONFLICT, "an order that is no longer open cannot be combined into a shipment")
    ShipmentError.OrderShippedForShipment ->
        failure(HttpStatus.CONFLICT, "an order that has already shipped cannot be combined into another shipment")
    ShipmentError.CombineNeedsTwoShipments -> failure(HttpStatus.BAD_REQUEST, "select at least two shipments to combine")
    ShipmentError.ShipmentShippedCannotCombine -> failure(HttpStatus.CONFLICT, "a shipment that has already shipped cannot be combined")
    ShipmentError.ShipmentShippedCannotSplit -> failure(HttpStatus.CONFLICT, "a shipment that has already shipped cannot be split")
    is ShipmentError.OrderNotInShipment -> failure(HttpStatus.BAD_REQUEST, "order $orderId does not belong to this shipment")
    ShipmentError.SplitNeedsSomeButNotAllOrders ->
        failure(HttpStatus.BAD_REQUEST, "split must move at least one order and leave at least one behind")
    ShipmentError.NotOwnerCannotRename -> failure(HttpStatus.FORBIDDEN, "only the shipment owner can rename it")
    ShipmentError.NotOwnerCannotSetShipping ->
        failure(HttpStatus.FORBIDDEN, "only the shipment owner can set its destination and shipping method")
    ShipmentError.ShippingLockedOnceShipped ->
        failure(HttpStatus.CONFLICT, "cannot change destination or shipping method once a shipment has shipped")
    ShipmentError.NotOwnerCannotMarkArrived -> failure(HttpStatus.FORBIDDEN, "only the shipment owner can mark it as arrived")
    ShipmentError.OnlyShippedCanArrive -> failure(HttpStatus.CONFLICT, "only a shipped shipment can be marked as arrived")
    ShipmentError.OrderNotInShipmentToLeave -> failure(HttpStatus.CONFLICT, "order is not part of a shipment")
    ShipmentError.LeaveAfterShipped -> failure(HttpStatus.CONFLICT, "cannot leave a shipment that has already shipped")
    ShipmentError.OrdersNotReadyToShip ->
        failure(HttpStatus.CONFLICT, "every order in this shipment must reach preparing-shipping before it can ship")
    ShipmentError.AttachToShippedShipment -> failure(HttpStatus.CONFLICT, "cannot attach an order to a shipment that has already shipped")
    ShipmentError.ShippingRateNotFound -> failure(HttpStatus.NOT_FOUND, "shipping rate not found")
    ShipmentError.ShippingRateExists ->
        failure(HttpStatus.CONFLICT, "a rate already exists for this destination, method and weight tier")
}

private fun CombineError.combineFailure(): HttpFailure = when (this) {
    CombineError.RequestNotFound -> failure(HttpStatus.NOT_FOUND, "combine request not found")
    CombineError.RequestNotPending -> failure(HttpStatus.CONFLICT, "combine request is no longer pending")
    CombineError.AlreadyOnShipment -> failure(HttpStatus.BAD_REQUEST, "you already have an order on this shipment")
    CombineError.TargetShipmentShipped -> failure(HttpStatus.CONFLICT, "a shipment that has already shipped cannot be combined into")
    CombineError.DuplicatePendingRequest ->
        failure(HttpStatus.CONFLICT, "a pending combine request already exists for this order and shipment")
    CombineError.MemberAlreadyOnShipment -> failure(HttpStatus.CONFLICT, "that member already has an order on this shipment")
    CombineError.OrderNotOpen -> failure(HttpStatus.CONFLICT, "an order that is no longer open cannot be combined")
    CombineError.OrderAlreadyShipped -> failure(HttpStatus.CONFLICT, "an order that has already shipped cannot be combined")
}

private fun AuthError.authFailure(): HttpFailure = when (this) {
    AuthError.InvalidCredentials -> failure(HttpStatus.UNAUTHORIZED, "invalid credentials")
    AuthError.InvalidAuthorizationCode -> failure(HttpStatus.UNAUTHORIZED, "invalid authorization code")
    AuthError.TooManyAttempts -> failure(HttpStatus.TOO_MANY_REQUESTS, "too many login attempts")
    AuthError.SsoNotEnabledUpstream -> failure(HttpStatus.SERVICE_UNAVAILABLE, "meishi sso is not enabled")
    AuthError.SsoDisabled -> failure(HttpStatus.NOT_FOUND, "meishi sso is disabled")
    AuthError.InvalidRequest -> failure(HttpStatus.BAD_REQUEST, "invalid request")
    AuthError.AccountFrozen -> failure(HttpStatus.FORBIDDEN, "account is frozen")
    AuthError.UnusableIdentityToken -> failure(HttpStatus.BAD_GATEWAY, "identity provider returned an unusable token")
}

private fun UserError.userFailure(): HttpFailure = when (this) {
    is UserError.UpstreamRejected -> HttpFailure(HttpStatusCode.valueOf(status), message)
    UserError.EmptyUpstreamResponse -> failure(HttpStatus.BAD_GATEWAY, "empty response from meishi")
}
