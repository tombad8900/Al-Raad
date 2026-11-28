package com.alraad.core.common.diagnostics

import com.alraad.core.common.contracts.DiagEntry
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

/**
 * Single-writer queue for the diagnostics file. [enqueue] never blocks, so it is safe
 * to call from WebView request threads; [runLoop] appends queued entries in batches.
 * [clear] and [flush] are ordered after every entry queued before them.
 */
internal class DiagnosticsFileWriter(
    private val file: File,
    private val lock: Any,
    private val format: (DiagEntry) -> String,
) {
    private sealed interface Op {
        class Append(val entry: DiagEntry) : Op

        class Clear(val done: CompletableDeferred<Unit>) : Op

        class Flush(val done: CompletableDeferred<Unit>) : Op
    }

    private val ops = Channel<Op>(Channel.UNLIMITED)

    fun enqueue(entry: DiagEntry) {
        ops.trySend(Op.Append(entry))
    }

    suspend fun flush() = await { Op.Flush(it) }

    suspend fun clear() = await { Op.Clear(it) }

    private suspend fun await(op: (CompletableDeferred<Unit>) -> Op) {
        val done = CompletableDeferred<Unit>()
        if (ops.trySend(op(done)).isSuccess) done.await()
    }

    suspend fun runLoop() {
        try {
            for (op in ops) {
                val pending = if (op is Op.Append) appendBatch(op) else op
                if (pending != null) complete(pending)
            }
        } finally {
            ops.close()
            while (true) complete(ops.tryReceive().getOrNull() ?: break)
        }
    }

    private fun appendBatch(first: Op.Append): Op? {
        val batch = StringBuilder(format(first.entry)).append('\n')
        var barrier: Op? = null
        while (barrier == null) {
            val next = ops.tryReceive().getOrNull() ?: break
            if (next is Op.Append) batch.append(format(next.entry)).append('\n') else barrier = next
        }
        synchronized(lock) {
            file.parentFile?.mkdirs()
            file.appendText(batch.toString())
        }
        return barrier
    }

    private fun complete(op: Op) {
        when (op) {
            is Op.Clear -> {
                synchronized(lock) { if (file.exists()) file.delete() }
                op.done.complete(Unit)
            }
            is Op.Flush -> op.done.complete(Unit)
            is Op.Append -> Unit
        }
    }
}
