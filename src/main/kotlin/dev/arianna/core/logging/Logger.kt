package dev.arianna.core.logging

interface Logger {
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, cause: Throwable? = null)
}

class ConsoleLogger(
    private val verbose: Boolean = false
) : Logger {
    override fun info(message: String) {
        if (verbose) System.err.println("info: $message")
    }

    override fun warn(message: String) {
        System.err.println("warning: $message")
    }

    override fun error(message: String, cause: Throwable?) {
        System.err.println("error: $message")
        if (verbose) cause?.printStackTrace(System.err)
    }
}
