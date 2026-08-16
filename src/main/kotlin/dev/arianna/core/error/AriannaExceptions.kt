package dev.arianna.core.error

open class AriannaException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

open class RepositoryException(message: String, cause: Throwable? = null) : AriannaException(message, cause)

class IndexingException(message: String, cause: Throwable? = null) : AriannaException(message, cause)

class StorageException(message: String, cause: Throwable? = null) : AriannaException(message, cause)

class QueryException(message: String, cause: Throwable? = null) : AriannaException(message, cause)
