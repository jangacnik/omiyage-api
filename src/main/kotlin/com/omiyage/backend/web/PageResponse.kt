package com.omiyage.backend.web

import org.springframework.data.domain.Page

data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

fun <T, R> Page<T>.toPageResponse(map: (T) -> R): PageResponse<R> =
    PageResponse(content.map(map), number, size, totalElements, totalPages)
