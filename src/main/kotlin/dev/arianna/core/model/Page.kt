package dev.arianna.core.model

data class Page<T>(
    val items: List<T>,
    val total: Int,
    val offset: Int,
    val limit: Int
) {
    val nextOffset: Int?
        get() = if (offset + items.size < total) offset + items.size else null
}
