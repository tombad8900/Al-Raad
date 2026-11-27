package com.alraad.core.common.diagnostics

import com.alraad.core.common.contracts.DiagCategory
import com.alraad.core.common.contracts.DiagLevel
import com.alraad.core.common.contracts.DiagnosticsLogger

/**
 * UncaughtExceptionHandler that records the full raw stack trace to
 * [DiagnosticsLogger], then delegates to the previously installed handler
 * so normal crash behaviour is preserved.
 */
class CrashReporter(
    private val logger: DiagnosticsLogger,
    private val previous: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler(),
) : Thread.UncaughtExceptionHandler {
    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(
        thread: Thread,
        error: Throwable,
    ) {
        runCatching {
            logger.log(
                DiagCategory.CRASH,
                DiagLevel.ERROR,
                "Uncaught exception in thread ${thread.name}",
                mapOf("stacktrace" to error.stackTraceToString()),
            )
        }
        previous?.uncaughtException(thread, error)
    }
}
