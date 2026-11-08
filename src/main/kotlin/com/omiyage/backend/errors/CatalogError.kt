package com.omiyage.backend.errors

sealed interface CatalogError : DomainError {
    data object CategoryNotFound : CatalogError
    data object CategorySlugInUse : CatalogError
    data object ItemTemplateNotFound : CatalogError
    data object InventoryItemNotFound : CatalogError
    data object PreorderNeedsReleaseDate : CatalogError
    data object UnknownCategoryIds : CatalogError
    data class TagTooLong(val maxLength: Int) : CatalogError
}
