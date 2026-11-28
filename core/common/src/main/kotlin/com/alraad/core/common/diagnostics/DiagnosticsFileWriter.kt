package com.alraad.core.common.diagnostics

import com.alraad.core.common.contracts.DiagEntry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

/**
 * Single-writer queue for the diagnostics file. [enqueue] never blocks, so it is safe
 * to call from WebView request threads; [runLoop] appends queued entries in batches.
 * [clear] and [flush] are ordered after every entry queued before them.
 *
 * Memory is bounded: when [maxPending] entries are already waiting (disk stalled),
 * further entries are not queued for the file (they still reach the in-memory list)
 * and are counted; a single note with that count is written once the writer catches up.
 * A failed disk write drops that batch and the writer keeps running.
 */
internal class DiagnosticsFileWriter(
    private val file: File,
    private val lock: Any,
    private val format: (DiagEntry) -> String,
    private val maxPending: Int = MAX_PENDING,
    private val droppedNote: (Int) -> DiagEntry? = { null },
) {
    private val pending = AtomicInteger(0)
    private val dropped = AtomicInteger(0)

    private sealed interface Op {
        class Append(val entry: DiagEntry) : Op

        class Clear(val done: CompletableDeferred<Unit>) : Op

        class Flush(val done: CompletableDeferred<Unit>) : Op
    }

    private val ops = Channel<Op>(Channel.UNLIMITED)

    fun enqueue(entry: DiagEntry) {
        if (pending.incrementAndGet() > maxPending) {
            pending.decrementAndGet()
            dropped.incrementAndGet()
            return
        }
        if (!ops.trySend(Op.Append(entry)).isSuccess) pending.decrementAndGet()
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
        var count = 1
        val batch = StringBuilder(format(first.entry)).append('\n')
        var barrier: Op? = null
        while (barrier == null) {
            val next = ops.tryReceive().getOrNull() ?: break
            if (next is Op.Append) {
                batch.append(format(next.entry)).append('\n')
                count++
            } else {
                barrier = next
            }
        }
        pending.addAndGet(-count)
        val lost = dropped.getAndSet(0)
        if (lost > 0) droppedNote(lost)?.let { batch.append(format(it)).append('\n') }
        // A failing disk must not kill the writer; this batch is lost, later ones still try.
        runCatching {
            synchronized(lock) {
                file.parentFile?.mkdirs()
                file.appendText(batch.toString())
            }
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

    private companion object {
        const val MAX_PENDING = 5_000
    }
}
