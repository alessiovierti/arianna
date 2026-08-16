package dev.arianna.core.api

data class IndexProgress(
    val stage: String,
    val completed: Int,
    val total: Int,
    val message: String
) {
    val percent: Int
        get() = if (total <= 0) 0 else (completed * 100 / total).coerceIn(0, 100)
}

fun interface IndexProgressListener {
    fun onProgress(progress: IndexProgress)
}

object NoopIndexProgressListener : IndexProgressListener {
    override fun onProgress(progress: IndexProgress) = Unit
}
