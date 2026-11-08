package com.omiyage.backend.errors

sealed interface BasketError : DomainError {
    data object LineNeedsItemOrTitle : BasketError
    data object CustomLineNeedsTitle : BasketError
    data object BasketEmpty : BasketError
    data object LineNotFound : BasketError
}
